/**
 * $RCSfile$
 * $Revision: 1623 $
 * $Date: 2005-07-12 18:40:57 -0300 (Tue, 12 Jul 2005) $
 *
 * Copyright (C) 2004-2008 Jive Software. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jivesoftware.openfire.muc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.jivesoftware.database.DbConnectionManager;
import org.jivesoftware.database.SequenceManager;
import org.jivesoftware.openfire.PacketRouter;
import org.jivesoftware.openfire.XMPPServer;
import org.jivesoftware.openfire.muc.ConflictException;
import org.jivesoftware.openfire.muc.ForbiddenException;
import org.jivesoftware.openfire.muc.MUCRole;
import org.jivesoftware.openfire.muc.MUCRoom;
import org.jivesoftware.openfire.muc.MultiUserChatService;
import org.jivesoftware.openfire.muc.NotAllowedException;
import org.jivesoftware.openfire.muc.spi.ConversationLogEntry;
import org.jivesoftware.openfire.muc.spi.LocalMUCRoom;
import org.jivesoftware.openfire.muc.spi.MUCServiceProperties;
import org.jivesoftware.openfire.muc.spi.MultiUserChatServiceImpl;
import org.jivesoftware.openfire.provider.MultiUserChatProvider;
import org.jivesoftware.util.JiveConstants;
import org.jivesoftware.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmpp.packet.JID;

/**
 * A manager responsible for ensuring room persistence. There are different ways to make a room 
 * persistent. The first attempt will be to save the room in a relation database. If for some reason
 * the room can't be saved in the database an alternative repository will be used to save the room
 * such as XML files.<p>
 * 
 * After the problem with the database has been solved, the information saved in the XML files will
 * be moved to the database.
 *
 * @author Gaston Dombiak
 */
public class DefaultMultiUserChatProvider implements MultiUserChatProvider {

    private static final Logger Log = LoggerFactory.getLogger(DefaultMultiUserChatProvider.class);

    // formerly in MultiUserChatmanager
    private static final String LOAD_SERVICES = "SELECT subdomain,description,isHidden FROM ofMucService";
    private static final String CREATE_SERVICE = "INSERT INTO ofMucService(serviceID,subdomain,description,isHidden) VALUES(?,?,?,?)";
    private static final String UPDATE_SERVICE = "UPDATE ofMucService SET subdomain=?,description=? WHERE serviceID=?";
    private static final String DELETE_SERVICE = "DELETE FROM ofMucService WHERE serviceID=?";
    private static final String LOAD_SERVICE_ID = "SELECT serviceID FROM ofMucService WHERE subdomain=?";
    private static final String LOAD_SUBDOMAIN = "SELECT subdomain FROM ofMucService WHERE serviceID=?";

    // formerly in MUCPersistenceManager
    private static final String GET_RESERVED_NAME =
        "SELECT nickname FROM ofMucMember WHERE roomID=? AND jid=?";
    private static final String LOAD_ROOM =
        "SELECT roomID, creationDate, modificationDate, naturalName, description, lockedDate, " +
        "emptyDate, canChangeSubject, maxUsers, publicRoom, moderated, membersOnly, canInvite, " +
        "roomPassword, canDiscoverJID, logEnabled, subject, rolesToBroadcast, useReservedNick, " +
        "canChangeNick, canRegister FROM ofMucRoom WHERE serviceID=? AND name=?";
    private static final String LOAD_AFFILIATIONS =
        "SELECT jid, affiliation FROM ofMucAffiliation WHERE roomID=?";
    private static final String LOAD_MEMBERS =
        "SELECT jid, nickname FROM ofMucMember WHERE roomID=?";
    private static final String LOAD_HISTORY =
        "SELECT sender, nickname, logTime, subject, body FROM ofMucConversationLog " +
        "WHERE logTime>? AND roomID=? AND (nickname IS NOT NULL OR subject IS NOT NULL) ORDER BY logTime";
    private static final String LOAD_ALL_ROOMS =
        "SELECT roomID, creationDate, modificationDate, name, naturalName, description, " +
        "lockedDate, emptyDate, canChangeSubject, maxUsers, publicRoom, moderated, membersOnly, " +
        "canInvite, roomPassword, canDiscoverJID, logEnabled, subject, rolesToBroadcast, " +
        "useReservedNick, canChangeNick, canRegister " +
        "FROM ofMucRoom WHERE serviceID=? AND (emptyDate IS NULL or emptyDate > ?)";
    private static final String LOAD_ALL_AFFILIATIONS =
        "SELECT ofMucAffiliation.roomID,ofMucAffiliation.jid,ofMucAffiliation.affiliation " +
        "FROM ofMucAffiliation,ofMucRoom WHERE ofMucAffiliation.roomID = ofMucRoom.roomID AND ofMucRoom.serviceID=?";
    private static final String LOAD_ALL_MEMBERS =
        "SELECT ofMucMember.roomID,ofMucMember.jid,ofMucMember.nickname FROM ofMucMember,ofMucRoom " +
        "WHERE ofMucMember.roomID = ofMucRoom.roomID AND ofMucRoom.serviceID=?";
    private static final String LOAD_ALL_HISTORY =
        "SELECT ofMucConversationLog.roomID, ofMucConversationLog.sender, ofMucConversationLog.nickname, " +
        "ofMucConversationLog.logTime, ofMucConversationLog.subject, ofMucConversationLog.body FROM " +
        "ofMucConversationLog, ofMucRoom WHERE ofMucConversationLog.roomID = ofMucRoom.roomID AND " +
        "ofMucRoom.serviceID=? AND ofMucConversationLog.logTime>? AND (ofMucConversationLog.nickname IS NOT NULL " +
        "OR ofMucConversationLog.subject IS NOT NULL) ORDER BY ofMucConversationLog.logTime";
    private static final String UPDATE_ROOM =
        "UPDATE ofMucRoom SET modificationDate=?, naturalName=?, description=?, " +
        "canChangeSubject=?, maxUsers=?, publicRoom=?, moderated=?, membersOnly=?, " +
        "canInvite=?, roomPassword=?, canDiscoverJID=?, logEnabled=?, rolesToBroadcast=?, " +
        "useReservedNick=?, canChangeNick=?, canRegister=? WHERE roomID=?";
    private static final String ADD_ROOM = 
        "INSERT INTO ofMucRoom (serviceID, roomID, creationDate, modificationDate, name, naturalName, " +
        "description, lockedDate, emptyDate, canChangeSubject, maxUsers, publicRoom, moderated, " +
        "membersOnly, canInvite, roomPassword, canDiscoverJID, logEnabled, subject, " +
        "rolesToBroadcast, useReservedNick, canChangeNick, canRegister) VALUES (?,?,?,?,?,?,?,?,?," +
            "?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
    private static final String UPDATE_SUBJECT =
        "UPDATE ofMucRoom SET subject=? WHERE roomID=?";
    private static final String UPDATE_LOCK =
        "UPDATE ofMucRoom SET lockedDate=? WHERE roomID=?";
    private static final String UPDATE_EMPTYDATE =
        "UPDATE ofMucRoom SET emptyDate=? WHERE roomID=?";
    private static final String DELETE_ROOM =
        "DELETE FROM ofMucRoom WHERE roomID=?";
    private static final String DELETE_AFFILIATIONS =
        "DELETE FROM ofMucAffiliation WHERE roomID=?";
    private static final String DELETE_MEMBERS =
        "DELETE FROM ofMucMember WHERE roomID=?";
    private static final String ADD_MEMBER =
        "INSERT INTO ofMucMember (roomID,jid,nickname) VALUES (?,?,?)";
    private static final String UPDATE_MEMBER =
        "UPDATE ofMucMember SET nickname=? WHERE roomID=? AND jid=?";
    private static final String DELETE_MEMBER =
        "DELETE FROM ofMucMember WHERE roomID=? AND jid=?";
    private static final String ADD_AFFILIATION =
        "INSERT INTO ofMucAffiliation (roomID,jid,affiliation) VALUES (?,?,?)";
    private static final String UPDATE_AFFILIATION =
        "UPDATE ofMucAffiliation SET affiliation=? WHERE roomID=? AND jid=?";
    private static final String DELETE_AFFILIATION =
        "DELETE FROM ofMucAffiliation WHERE roomID=? AND jid=?";
    private static final String DELETE_USER_MEMBER =
        "DELETE FROM ofMucMember WHERE jid=?";
    private static final String DELETE_USER_MUCAFFILIATION =
        "DELETE FROM ofMucAffiliation WHERE jid=?";
    private static final String ADD_CONVERSATION_LOG =
        "INSERT INTO ofMucConversationLog (roomID,sender,nickname,logTime,subject,body) " +
        "VALUES (?,?,?,?,?,?)";

    // formerly in MUCServiceProperties
    private static final String LOAD_PROPERTIES = "SELECT name, propValue FROM ofMucServiceProp WHERE serviceID=?";
    private static final String INSERT_PROPERTY = "INSERT INTO ofMucServiceProp(serviceID, name, propValue) VALUES(?,?,?)";
    private static final String UPDATE_PROPERTY = "UPDATE ofMucServiceProp SET propValue=? WHERE serviceID=? AND name=?";
    private static final String DELETE_PROPERTY = "DELETE FROM ofMucServiceProp WHERE serviceID=? AND name=?";

    /* Map of subdomains to their associated properties */
    private static ConcurrentHashMap<String,MUCServiceProperties> propertyMaps = new ConcurrentHashMap<String,MUCServiceProperties>();

    // formerly in MultiUserChatmanager
    /**
     * {@inheritDoc}
     */
    public Map<String,MultiUserChatService> loadServices() {
        Connection con = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        Map<String,MultiUserChatService> mucServices = new HashMap<String,MultiUserChatService>();
        try {
            con = DbConnectionManager.getConnection();
            pstmt = con.prepareStatement(LOAD_SERVICES);
            rs = pstmt.executeQuery();
            while (rs.next()) {
                String subdomain = rs.getString(1);
                String description = rs.getString(2);
                Boolean isHidden = Boolean.valueOf(rs.getString(3));
                MultiUserChatServiceImpl muc = new MultiUserChatServiceImpl(subdomain, description, isHidden);
                mucServices.put(subdomain, muc);
            }
        }
        catch (Exception e) {
            Log.error(e.getMessage(), e);
        }
        finally {
            DbConnectionManager.closeConnection(rs, pstmt, con);
        }

        return mucServices;
    }

    /**
     * {@inheritDoc}
     */
    public long loadServiceID(String subdomain) {
        Connection con = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        Long id = (long)-1;
        try {
            con = DbConnectionManager.getConnection();
            pstmt = con.prepareStatement(LOAD_SERVICE_ID);
            pstmt.setString(1, subdomain);
            rs = pstmt.executeQuery();
            if (rs.next()) {
                id = rs.getLong(1);
            }
            else {
                throw new Exception("Unable to locate Service ID for subdomain "+subdomain);
            }
        }
        catch (Exception e) {
            // No problem, considering this as a "not found".
        }
        finally {
            DbConnectionManager.closeConnection(rs, pstmt, con);
        }
        return id;
    }

    /**
     * {@inheritDoc}
     */
    public String loadServiceSubdomain(Long serviceID) {
        Connection con = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        String subdomain = null;
        try {
            con = DbConnectionManager.getConnection();
            pstmt = con.prepareStatement(LOAD_SUBDOMAIN);
            pstmt.setLong(1, serviceID);
            rs = pstmt.executeQuery();
            if (rs.next()) {
                subdomain = rs.getString(1);
            }
            else {
                throw new Exception("Unable to locate subdomain for service ID "+serviceID);
            }
        }
        catch (Exception e) {
            // No problem, considering this as a "not found".
        }
        finally {
            DbConnectionManager.closeConnection(rs, pstmt, con);
        }
        return subdomain;
    }

    /**
     * {@inheritDoc}
     */
    public void insertService(String subdomain, String description, Boolean isHidden) {
        Connection con = null;
        PreparedStatement pstmt = null;
        Long serviceID = SequenceManager.nextID(JiveConstants.MUC_SERVICE);
        try {
            con = DbConnectionManager.getConnection();
            pstmt = con.prepareStatement(CREATE_SERVICE);
            pstmt.setLong(1, serviceID);
            pstmt.setString(2, subdomain);
            if (description != null) {
                pstmt.setString(3, description);
            }
           else {
                pstmt.setNull(3, Types.VARCHAR);
            }
            pstmt.setInt(4, (isHidden ? 1 : 0));
            pstmt.executeUpdate();
        }
        catch (SQLException e) {
            Log.error(e.getMessage(), e);
        }
        finally {
            DbConnectionManager.closeConnection(pstmt, con);
        }
    }

    /**
     * {@inheritDoc}
     */
    public void updateService(Long serviceID, String subdomain, String description) {
        Connection con = null;
        PreparedStatement pstmt = null;
        try {
            con = DbConnectionManager.getConnection();
            pstmt = con.prepareStatement(UPDATE_SERVICE);
            pstmt.setString(1, subdomain);
            if (description != null) {
                pstmt.setString(2, description);
            }
            else {
                pstmt.setNull(2, Types.VARCHAR);
            }
            pstmt.setLong(3, serviceID);
            pstmt.executeUpdate();
        }
        catch (SQLException e) {
            Log.error(e.getMessage(), e);
        }
        finally {
            DbConnectionManager.closeConnection(pstmt, con);
        }
    }

    /**
     * {@inheritDoc}
     */
    public void deleteService(Long serviceID) {
        Connection con = null;
        PreparedStatement pstmt = null;
        try {
            con = DbConnectionManager.getConnection();
            pstmt = con.prepareStatement(DELETE_SERVICE);
            pstmt.setLong(1, serviceID);
            pstmt.executeUpdate();
        }
        catch (SQLException e) {
            Log.error(e.getMessage(), e);
        }
        finally {
            DbConnectionManager.closeConnection(pstmt, con);
        }
    }

    // formerly from MUCPersistenceManager
    /**
     * {@inheritDoc}
     */
    public String getReservedNickname(MUCRoom room, String bareJID) {
        Connection con = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        String answer = null;
        try {
            con = DbConnectionManager.getConnection();
            pstmt = con.prepareStatement(GET_RESERVED_NAME);
            pstmt.setLong(1, room.getID());
            pstmt.setString(2, bareJID);
            rs = pstmt.executeQuery();
            if (rs.next()) {
                answer = rs.getString(1);
            }
        }
        catch (SQLException sqle) {
            Log.error(sqle.getMessage(), sqle);
        }
        finally {
            DbConnectionManager.closeConnection(rs, pstmt, con);
        }
        return answer;
    }

    /**
     * {@inheritDoc}
     */
    public void loadFromDB(LocalMUCRoom room) {
        Connection con = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        try {
            Long serviceID = XMPPServer.getInstance().getMultiUserChatManager().getMultiUserChatServiceID(room.getMUCService().getServiceName());
            con = DbConnectionManager.getConnection();
            pstmt = con.prepareStatement(LOAD_ROOM);
            pstmt.setLong(1, serviceID);
            pstmt.setString(2, room.getName());
            rs = pstmt.executeQuery();
            if (!rs.next()) {
                throw new IllegalArgumentException("Room " + room.getName() + " was not found in the database.");
            }
            room.setID(rs.getLong(1));
            room.setCreationDate(new Date(Long.parseLong(rs.getString(2).trim()))); // creation date
            room.setModificationDate(new Date(Long.parseLong(rs.getString(3).trim()))); // modification date
            room.setNaturalLanguageName(rs.getString(4));
            room.setDescription(rs.getString(5));
            room.setLockedDate(new Date(Long.parseLong(rs.getString(6).trim())));
            if (rs.getString(7) != null) {
                room.setEmptyDate(new Date(Long.parseLong(rs.getString(7).trim())));
            }
            else {
                room.setEmptyDate(null);
            }
            room.setCanOccupantsChangeSubject(rs.getInt(8) == 1);
            room.setMaxUsers(rs.getInt(9));
            room.setPublicRoom(rs.getInt(10) == 1);
            room.setModerated(rs.getInt(11) == 1);
            room.setMembersOnly(rs.getInt(12) == 1);
            room.setCanOccupantsInvite(rs.getInt(13) == 1);
            room.setPassword(rs.getString(14));
            room.setCanAnyoneDiscoverJID(rs.getInt(15) == 1);
            room.setLogEnabled(rs.getInt(16) == 1);
            room.setSubject(rs.getString(17));
            List<String> rolesToBroadcast = new ArrayList<String>();
            String roles = Integer.toBinaryString(rs.getInt(18));
            if (roles.charAt(0) == '1') {
                rolesToBroadcast.add("moderator");
            }
            if (roles.length() > 1 && roles.charAt(1) == '1') {
                rolesToBroadcast.add("participant");
            }
            if (roles.length() > 2 && roles.charAt(2) == '1') {
                rolesToBroadcast.add("visitor");
            }
            room.setRolesToBroadcastPresence(rolesToBroadcast);
            room.setLoginRestrictedToNickname(rs.getInt(19) == 1);
            room.setChangeNickname(rs.getInt(20) == 1);
            room.setRegistrationEnabled(rs.getInt(21) == 1);
            room.setPersistent(true);
            DbConnectionManager.fastcloseStmt(rs, pstmt);

            pstmt = con.prepareStatement(LOAD_HISTORY);
            // Recreate the history until two days ago
            long from = System.currentTimeMillis() - (86400000 * 2);
            pstmt.setString(1, StringUtils.dateToMillis(new Date(from)));
            pstmt.setLong(2, room.getID());
            rs = pstmt.executeQuery();
            while (rs.next()) {
                String senderJID = rs.getString(1);
                String nickname = rs.getString(2);
                Date sentDate = new Date(Long.parseLong(rs.getString(3).trim()));
                String subject = rs.getString(4);
                String body = rs.getString(5);
                // Recreate the history only for the rooms that have the conversation logging
                // enabled
                if (room.isLogEnabled()) {
                    room.getRoomHistory().addOldMessage(senderJID, nickname, sentDate, subject,
                            body);
                }
            }
            DbConnectionManager.fastcloseStmt(rs, pstmt);

            // If the room does not include the last subject in the history then recreate one if
            // possible
            if (!room.getRoomHistory().hasChangedSubject() && room.getSubject() != null &&
                    room.getSubject().length() > 0) {
                room.getRoomHistory().addOldMessage(room.getRole().getRoleAddress().toString(),
                        null, room.getModificationDate(), room.getSubject(), null);
            }

            pstmt = con.prepareStatement(LOAD_AFFILIATIONS);
            pstmt.setLong(1, room.getID());
            rs = pstmt.executeQuery();
            while (rs.next()) {
                JID jid = new JID(rs.getString(1));
                MUCRole.Affiliation affiliation = MUCRole.Affiliation.valueOf(rs.getInt(2));
                try {
                    switch (affiliation) {
                        case owner:
                            room.addOwner(jid, room.getRole());
                            break;
                        case admin:
                            room.addAdmin(jid, room.getRole());
                            break;
                        case outcast:
                            room.addOutcast(jid, null, room.getRole());
                            break;
                        default:
                            Log.error("Unkown affiliation value " + affiliation + " for user "
                                    + jid.toBareJID() + " in persistent room " + room.getID());
                    }
                }
                catch (Exception e) {
                    Log.error(e.getMessage(), e);
                }
            }
            DbConnectionManager.fastcloseStmt(rs, pstmt);
            
            pstmt = con.prepareStatement(LOAD_MEMBERS);
            pstmt.setLong(1, room.getID());
            rs = pstmt.executeQuery();
            while (rs.next()) {
                try {
                    room.addMember(new JID(rs.getString(1)), rs.getString(2), room.getRole());
                }
                catch (Exception e) {
                    Log.error(e.getMessage(), e);
                }
            }
            // Set now that the room's configuration is updated in the database. Note: We need to
            // set this now since otherwise the room's affiliations will be saved to the database
            // "again" while adding them to the room!
            room.setSavedToDB(true);
            if (room.getEmptyDate() == null) {
                // The service process was killed somehow while the room was being used. Since
                // the room won't have occupants at this time we need to set the best date when
                // the last occupant left the room that we can
                room.setEmptyDate(new Date());
            }
        }
        catch (SQLException sqle) {
            Log.error(sqle.getMessage(), sqle);
        }
        finally {
            DbConnectionManager.closeConnection(rs, pstmt, con);
        }
    }

    /**
     * {@inheritDoc}
     */
    public void saveToDB(LocalMUCRoom room) {
        Connection con = null;
        PreparedStatement pstmt = null;
        try {
            con = DbConnectionManager.getConnection();
            if (room.wasSavedToDB()) {
                pstmt = con.prepareStatement(UPDATE_ROOM);
                pstmt.setString(1, StringUtils.dateToMillis(room.getModificationDate()));
                pstmt.setString(2, room.getNaturalLanguageName());
                pstmt.setString(3, room.getDescription());
                pstmt.setInt(4, (room.canOccupantsChangeSubject() ? 1 : 0));
                pstmt.setInt(5, room.getMaxUsers());
                pstmt.setInt(6, (room.isPublicRoom() ? 1 : 0));
                pstmt.setInt(7, (room.isModerated() ? 1 : 0));
                pstmt.setInt(8, (room.isMembersOnly() ? 1 : 0));
                pstmt.setInt(9, (room.canOccupantsInvite() ? 1 : 0));
                pstmt.setString(10, room.getPassword());
                pstmt.setInt(11, (room.canAnyoneDiscoverJID() ? 1 : 0));
                pstmt.setInt(12, (room.isLogEnabled() ? 1 : 0));
                pstmt.setInt(13, marshallRolesToBroadcast(room));
                pstmt.setInt(14, (room.isLoginRestrictedToNickname() ? 1 : 0));
                pstmt.setInt(15, (room.canChangeNickname() ? 1 : 0));
                pstmt.setInt(16, (room.isRegistrationEnabled() ? 1 : 0));
                pstmt.setLong(17, room.getID());
                pstmt.executeUpdate();
            }
            else {
                pstmt = con.prepareStatement(ADD_ROOM);
                pstmt.setLong(1, XMPPServer.getInstance().getMultiUserChatManager().getMultiUserChatServiceID(room.getMUCService().getServiceName()));
                pstmt.setLong(2, room.getID());
                pstmt.setString(3, StringUtils.dateToMillis(room.getCreationDate()));
                pstmt.setString(4, StringUtils.dateToMillis(room.getModificationDate()));
                pstmt.setString(5, room.getName());
                pstmt.setString(6, room.getNaturalLanguageName());
                pstmt.setString(7, room.getDescription());
                pstmt.setString(8, StringUtils.dateToMillis(room.getLockedDate()));
                Date emptyDate = room.getEmptyDate();
                if (emptyDate == null) {
                    pstmt.setString(9, null);
                }
                else {
                    pstmt.setString(9, StringUtils.dateToMillis(emptyDate));
                }
                pstmt.setInt(10, (room.canOccupantsChangeSubject() ? 1 : 0));
                pstmt.setInt(11, room.getMaxUsers());
                pstmt.setInt(12, (room.isPublicRoom() ? 1 : 0));
                pstmt.setInt(13, (room.isModerated() ? 1 : 0));
                pstmt.setInt(14, (room.isMembersOnly() ? 1 : 0));
                pstmt.setInt(15, (room.canOccupantsInvite() ? 1 : 0));
                pstmt.setString(16, room.getPassword());
                pstmt.setInt(17, (room.canAnyoneDiscoverJID() ? 1 : 0));
                pstmt.setInt(18, (room.isLogEnabled() ? 1 : 0));
                pstmt.setString(19, room.getSubject());
                pstmt.setInt(20, marshallRolesToBroadcast(room));
                pstmt.setInt(21, (room.isLoginRestrictedToNickname() ? 1 : 0));
                pstmt.setInt(22, (room.canChangeNickname() ? 1 : 0));
                pstmt.setInt(23, (room.isRegistrationEnabled() ? 1 : 0));
                pstmt.executeUpdate();
            }
        }
        catch (SQLException sqle) {
            Log.error(sqle.getMessage(), sqle);
        }
        finally {
            DbConnectionManager.closeConnection(pstmt, con);
        }
    }

    /**
     * {@inheritDoc}
     */
    public void deleteFromDB(MUCRoom room) {
        if (!room.isPersistent() || !room.wasSavedToDB()) {
            return;
        }
        Connection con = null;
        PreparedStatement pstmt = null;
        boolean abortTransaction = false;
        try {
            con = DbConnectionManager.getTransactionConnection();
            pstmt = con.prepareStatement(DELETE_AFFILIATIONS);
            pstmt.setLong(1, room.getID());
            pstmt.executeUpdate();
            DbConnectionManager.fastcloseStmt(pstmt);

            pstmt = con.prepareStatement(DELETE_MEMBERS);
            pstmt.setLong(1, room.getID());
            pstmt.executeUpdate();
            DbConnectionManager.fastcloseStmt(pstmt);

            pstmt = con.prepareStatement(DELETE_ROOM);
            pstmt.setLong(1, room.getID());
            pstmt.executeUpdate();

            // Update the room (in memory) to indicate the it's no longer in the database.
            room.setSavedToDB(false);
        }
        catch (SQLException sqle) {
            Log.error(sqle.getMessage(), sqle);
            abortTransaction = true;
        }
        finally {
            DbConnectionManager.closeStatement(pstmt);
            DbConnectionManager.closeTransactionConnection(con, abortTransaction);
        }
    }

    /**
     * {@inheritDoc}
     */
    public Collection<LocalMUCRoom> loadRoomsFromDB(MultiUserChatService chatserver, Date emptyDate, PacketRouter packetRouter) {
        Long serviceID = XMPPServer.getInstance().getMultiUserChatManager().getMultiUserChatServiceID(chatserver.getServiceName());

        final Map<Long, LocalMUCRoom> rooms;
        try {
            rooms = loadRooms(serviceID, emptyDate, chatserver, packetRouter);
            loadHistory(serviceID, rooms);
            loadAffiliations(serviceID, rooms);
            loadMembers(serviceID, rooms);
        }
        catch (SQLException sqle) {
            Log.error("A database error prevented MUC rooms to be loaded from the database.", sqle);
            return Collections.emptyList();
        }

        // Set now that the room's configuration is updated in the database. Note: We need to
        // set this now since otherwise the room's affiliations will be saved to the database
        // "again" while adding them to the room!
        for (final MUCRoom room : rooms.values()) {
            room.setSavedToDB(true);
            if (room.getEmptyDate() == null) {
                // The service process was killed somehow while the room was being used. Since
                // the room won't have occupants at this time we need to set the best date when
                // the last occupant left the room that we can
                room.setEmptyDate(new Date());
            }
        }

        return rooms.values();
    }

    private static Map<Long, LocalMUCRoom> loadRooms(Long serviceID, Date emptyDate, MultiUserChatService chatserver, PacketRouter packetRouter) throws SQLException {
        final Map<Long, LocalMUCRoom> rooms = new HashMap<Long, LocalMUCRoom>();

        Connection connection = null;
        PreparedStatement statement = null;
        ResultSet resultSet = null;
        try {
            connection = DbConnectionManager.getConnection();
            statement = connection.prepareStatement(LOAD_ALL_ROOMS);
            statement.setLong(1, serviceID);
            statement.setString(2, StringUtils.dateToMillis(emptyDate));
            resultSet = statement.executeQuery();

            while (resultSet.next()) {
                try {
                    LocalMUCRoom room = new LocalMUCRoom(chatserver, resultSet.getString(4), packetRouter);
                    room.setID(resultSet.getLong(1));
                    room.setCreationDate(new Date(Long.parseLong(resultSet.getString(2).trim()))); // creation date
                    room.setModificationDate(new Date(Long.parseLong(resultSet.getString(3).trim()))); // modification date
                    room.setNaturalLanguageName(resultSet.getString(5));
                    room.setDescription(resultSet.getString(6));
                    room.setLockedDate(new Date(Long.parseLong(resultSet.getString(7).trim())));
                    if (resultSet.getString(8) != null) {
                        room.setEmptyDate(new Date(Long.parseLong(resultSet.getString(8).trim())));
                    }
                    else {
                        room.setEmptyDate(null);
                    }
                    room.setCanOccupantsChangeSubject(resultSet.getInt(9) == 1);
                    room.setMaxUsers(resultSet.getInt(10));
                    room.setPublicRoom(resultSet.getInt(11) == 1);
                    room.setModerated(resultSet.getInt(12) == 1);
                    room.setMembersOnly(resultSet.getInt(13) == 1);
                    room.setCanOccupantsInvite(resultSet.getInt(14) == 1);
                    room.setPassword(resultSet.getString(15));
                    room.setCanAnyoneDiscoverJID(resultSet.getInt(16) == 1);
                    room.setLogEnabled(resultSet.getInt(17) == 1);
                    room.setSubject(resultSet.getString(18));
                    List<String> rolesToBroadcast = new ArrayList<String>();
                    String roles = Integer.toBinaryString(resultSet.getInt(19));
                    if (roles.charAt(0) == '1') {
                        rolesToBroadcast.add("moderator");
                    }
                    if (roles.length() > 1 && roles.charAt(1) == '1') {
                        rolesToBroadcast.add("participant");
                    }
                    if (roles.length() > 2 && roles.charAt(2) == '1') {
                        rolesToBroadcast.add("visitor");
                    }
                    room.setRolesToBroadcastPresence(rolesToBroadcast);
                    room.setLoginRestrictedToNickname(resultSet.getInt(20) == 1);
                    room.setChangeNickname(resultSet.getInt(21) == 1);
                    room.setRegistrationEnabled(resultSet.getInt(22) == 1);
                    room.setPersistent(true);
                    rooms.put(room.getID(), room);
                } catch (SQLException e) {
                    Log.error("A database exception prevented one particular MUC room to be loaded from the database.", e);
                }
            }
        } finally {
            DbConnectionManager.closeConnection(resultSet, statement, connection);
        }

        return rooms;
    }

    private static void loadHistory(Long serviceID, Map<Long, LocalMUCRoom> rooms) throws SQLException {
        Connection connection = null;
        PreparedStatement statement = null;
        ResultSet resultSet = null;
        try {
            connection = DbConnectionManager.getConnection();
            statement = connection.prepareStatement(LOAD_ALL_HISTORY);

            final long from = System.currentTimeMillis() - (86400000 * 2); // Recreate the history until two days ago
            statement.setLong(1, serviceID);
            statement.setString(2, StringUtils.dateToMillis(new Date(from)));
            resultSet = statement.executeQuery();

            while (resultSet.next()) {
                try {
                    LocalMUCRoom room = rooms.get(resultSet.getLong(1));
                    // Skip to the next position if the room does not exist
                    if (room == null) {
                        continue;
                    }
                    String senderJID = resultSet.getString(2);
                    String nickname  = resultSet.getString(3);
                    Date sentDate    = new Date(Long.parseLong(resultSet.getString(4).trim()));
                    String subject   = resultSet.getString(5);
                    String body      = resultSet.getString(6);
                    // Recreate the history only for the rooms that have the conversation logging enabled.
                    if (room.isLogEnabled()) {
                        room.getRoomHistory().addOldMessage(senderJID, nickname, sentDate, subject, body);
                    }
                } catch (SQLException e) {
                    Log.warn("A database exception prevented the history for one particular MUC room to be loaded from the database.", e);
                }
            }
        } finally {
            DbConnectionManager.closeConnection(resultSet, statement, connection);
        }

        // Add the last known room subject to the room history only for those rooms that still
        // don't have in their histories the last room subject
        for (MUCRoom loadedRoom : rooms.values())
        {
            if (!loadedRoom.getRoomHistory().hasChangedSubject()
                && loadedRoom.getSubject() != null
                && loadedRoom.getSubject().length() > 0)
            {
                loadedRoom.getRoomHistory().addOldMessage(  loadedRoom.getRole().getRoleAddress().toString(),
                                                            null,
                                                            loadedRoom.getModificationDate(),
                                                            loadedRoom.getSubject(),
                                                            null);
            }
        }
    }

    private static void loadAffiliations(Long serviceID, Map<Long, LocalMUCRoom> rooms) throws SQLException {
        Connection connection = null;
        PreparedStatement statement = null;
        ResultSet resultSet = null;
        try {
            connection = DbConnectionManager.getConnection();
            statement = connection.prepareStatement(LOAD_ALL_AFFILIATIONS);
            statement.setLong(1, serviceID);
            resultSet = statement.executeQuery();

            while (resultSet.next()) {
                try {
                    long roomID = resultSet.getLong(1);
                    LocalMUCRoom room = rooms.get(roomID);
                    // Skip to the next position if the room does not exist
                    if (room == null) {
                        continue;
                    }

                    final MUCRole.Affiliation affiliation = MUCRole.Affiliation.valueOf(resultSet.getInt(3));

                    final String jidValue = resultSet.getString(2);
                    final JID jid;
                    try {
                        jid = new JID(jidValue);
                    } catch (IllegalArgumentException ex) {
                        Log.warn("An illegal JID ({}) was found in the database, "
                                + "while trying to load all affiliations for room "
                                + "{}. The JID is ignored."
                                , new Object[] { jidValue, roomID });
                        continue;
                    }

                    try {
                        switch (affiliation) {
                            case owner:
                                room.addOwner(jid, room.getRole());
                                break;
                            case admin:
                                room.addAdmin(jid, room.getRole());
                                break;
                            case outcast:
                                room.addOutcast(jid, null, room.getRole());
                                break;
                            default:
                                Log.error("Unknown affiliation value " + affiliation + " for user " + jid + " in persistent room " + room.getID());
                        }
                    } catch (ForbiddenException e) {
                        Log.warn("An exception prevented affiliations to be added to the room with id " + roomID, e);
                    } catch (ConflictException e) {
                        Log.warn("An exception prevented affiliations to be added to the room with id " + roomID, e);
                    } catch (NotAllowedException e) {
                        Log.warn("An exception prevented affiliations to be added to the room with id " + roomID, e);
                    }
                } catch (SQLException e) {
                    Log.error("A database exception prevented affiliations for one particular MUC room to be loaded from the database.", e);
                }
            }

        } finally {
            DbConnectionManager.closeConnection(resultSet, statement, connection);
        }
    }

    private static void loadMembers(Long serviceID, Map<Long, LocalMUCRoom> rooms) throws SQLException {
        Connection connection = null;
        PreparedStatement statement = null;
        ResultSet resultSet = null;
        try {
            connection = DbConnectionManager.getConnection();
            statement = connection.prepareStatement(LOAD_ALL_MEMBERS);
            statement.setLong(1, serviceID);
            resultSet = statement.executeQuery();

            while (resultSet.next()) {
                try {
                    LocalMUCRoom room = rooms.get(resultSet.getLong(1));
                    // Skip to the next position if the room does not exist
                    if (room == null) {
                        continue;
                    }
                    try {
                        room.addMember(new JID(resultSet.getString(2)), resultSet.getString(3), room.getRole());
                    } catch (ForbiddenException e) {
                        Log.warn("Unable to add member to room.", e);
                    } catch (ConflictException e) {
                        Log.warn("Unable to add member to room.", e);
                    }
                } catch (SQLException e) {
                    Log.error("A database exception prevented members for one particular MUC room to be loaded from the database.", e);
                }
            }
        } finally {
            DbConnectionManager.closeConnection(resultSet, statement, connection);
        }
    }

    /**
     * {@inheritDoc}
     */
    public void updateRoomSubject(MUCRoom room) {
        if (!room.isPersistent() || !room.wasSavedToDB()) {
            return;
        }

        Connection con = null;
        PreparedStatement pstmt = null;
        try {
            con = DbConnectionManager.getConnection();
            pstmt = con.prepareStatement(UPDATE_SUBJECT);
            pstmt.setString(1, room.getSubject());
            pstmt.setLong(2, room.getID());
            pstmt.executeUpdate();
        }
        catch (SQLException sqle) {
            Log.error(sqle.getMessage(), sqle);
        }
        finally {
            DbConnectionManager.closeConnection(pstmt, con);
        }
    }

    /**
     * {@inheritDoc}
     */
    public void updateRoomLock(LocalMUCRoom room) {
        if (!room.isPersistent() || !room.wasSavedToDB()) {
            return;
        }

        Connection con = null;
        PreparedStatement pstmt = null;
        try {
            con = DbConnectionManager.getConnection();
            pstmt = con.prepareStatement(UPDATE_LOCK);
            pstmt.setString(1, StringUtils.dateToMillis(room.getLockedDate()));
            pstmt.setLong(2, room.getID());
            pstmt.executeUpdate();
        }
        catch (SQLException sqle) {
            Log.error(sqle.getMessage(), sqle);
        }
        finally {
            DbConnectionManager.closeConnection(pstmt, con);
        }
    }

    /**
     * {@inheritDoc}
     */
    public void updateRoomEmptyDate(MUCRoom room) {
        if (!room.isPersistent() || !room.wasSavedToDB()) {
            return;
        }

        Connection con = null;
        PreparedStatement pstmt = null;
        try {
            con = DbConnectionManager.getConnection();
            pstmt = con.prepareStatement(UPDATE_EMPTYDATE);
            Date emptyDate = room.getEmptyDate();
            if (emptyDate == null) {
                pstmt.setString(1, null);
            }
            else {
                pstmt.setString(1, StringUtils.dateToMillis(emptyDate));
            }
            pstmt.setLong(2, room.getID());
            pstmt.executeUpdate();
        }
        catch (SQLException sqle) {
            Log.error(sqle.getMessage(), sqle);
        }
        finally {
            DbConnectionManager.closeConnection(pstmt, con);
        }
    }

    /**
     * {@inheritDoc}
     */
    public void saveAffiliationToDB(MUCRoom room, JID jid, String nickname,
            MUCRole.Affiliation newAffiliation, MUCRole.Affiliation oldAffiliation)
    {
    	final String bareJID = jid.toBareJID();
        if (!room.isPersistent() || !room.wasSavedToDB()) {
            return;
        }
        if (MUCRole.Affiliation.none == oldAffiliation) {
            if (MUCRole.Affiliation.member == newAffiliation) {
                // Add the user to the members table
                Connection con = null;
                PreparedStatement pstmt = null;
                try {
                    con = DbConnectionManager.getConnection();
                    pstmt = con.prepareStatement(ADD_MEMBER);
                    pstmt.setLong(1, room.getID());
                    pstmt.setString(2, bareJID);
                    pstmt.setString(3, nickname);
                    pstmt.executeUpdate();
                }
                catch (SQLException sqle) {
                    Log.error(sqle.getMessage(), sqle);
                }
                finally {
                    DbConnectionManager.closeConnection(pstmt, con);
                }
            }
            else {
                // Add the user to the generic affiliations table
                Connection con = null;
                PreparedStatement pstmt = null;
                try {
                    con = DbConnectionManager.getConnection();
                    pstmt = con.prepareStatement(ADD_AFFILIATION);
                    pstmt.setLong(1, room.getID());
                    pstmt.setString(2, bareJID);
                    pstmt.setInt(3, newAffiliation.getValue());
                    pstmt.executeUpdate();
                }
                catch (SQLException sqle) {
                    Log.error(sqle.getMessage(), sqle);
                }
                finally {
                    DbConnectionManager.closeConnection(pstmt, con);
                }
            }
        }
        else {
            if (MUCRole.Affiliation.member == newAffiliation &&
                    MUCRole.Affiliation.member == oldAffiliation)
            {
                // Update the member's data in the member table.
                Connection con = null;
                PreparedStatement pstmt = null;
                try {
                    con = DbConnectionManager.getConnection();
                    pstmt = con.prepareStatement(UPDATE_MEMBER);
                    pstmt.setString(1, nickname);
                    pstmt.setLong(2, room.getID());
                    pstmt.setString(3, bareJID);
                    pstmt.executeUpdate();
                }
                catch (SQLException sqle) {
                    Log.error(sqle.getMessage(), sqle);
                }
                finally {
                    DbConnectionManager.closeConnection(pstmt, con);
                }
            }
            else if (MUCRole.Affiliation.member == newAffiliation) {
                Connection con = null;
                PreparedStatement pstmt = null;
                boolean abortTransaction = false;
                try {
                    // Remove the user from the generic affiliations table
                    con = DbConnectionManager.getTransactionConnection();
                    pstmt = con.prepareStatement(DELETE_AFFILIATION);
                    pstmt.setLong(1, room.getID());
                    pstmt.setString(2, bareJID);
                    pstmt.executeUpdate();
                    DbConnectionManager.fastcloseStmt(pstmt);

                    // Add them as a member.
                    pstmt = con.prepareStatement(ADD_MEMBER);
                    pstmt.setLong(1, room.getID());
                    pstmt.setString(2, bareJID);
                    pstmt.setString(3, nickname);
                    pstmt.executeUpdate();
                }
                catch (SQLException sqle) {
                    Log.error(sqle.getMessage(), sqle);
                    abortTransaction = true;
                }
                finally {
                    DbConnectionManager.closeStatement(pstmt);
                    DbConnectionManager.closeTransactionConnection(con, abortTransaction);
                }
            }
            else if (MUCRole.Affiliation.member == oldAffiliation) {
                Connection con = null;
                PreparedStatement pstmt = null;
                boolean abortTransaction = false;
                try {
                    con = DbConnectionManager.getTransactionConnection();
                    pstmt = con.prepareStatement(DELETE_MEMBER);
                    pstmt.setLong(1, room.getID());
                    pstmt.setString(2, bareJID);
                    pstmt.executeUpdate();
                    DbConnectionManager.fastcloseStmt(pstmt);

                    pstmt = con.prepareStatement(ADD_AFFILIATION);
                    pstmt.setLong(1, room.getID());
                    pstmt.setString(2, bareJID);
                    pstmt.setInt(3, newAffiliation.getValue());
                    pstmt.executeUpdate();
                }
                catch (SQLException sqle) {
                    Log.error(sqle.getMessage(), sqle);
                    abortTransaction = true;
                }
                finally {
                    DbConnectionManager.closeStatement(pstmt);
                    DbConnectionManager.closeTransactionConnection(con, abortTransaction);
                }
            }
            else {
                // Update the user in the generic affiliations table.
                Connection con = null;
                PreparedStatement pstmt = null;
                try {
                    con = DbConnectionManager.getConnection();
                    pstmt = con.prepareStatement(UPDATE_AFFILIATION);
                    pstmt.setInt(1, newAffiliation.getValue());
                    pstmt.setLong(2, room.getID());
                    pstmt.setString(3, bareJID);
                    pstmt.executeUpdate();
                }
                catch (SQLException sqle) {
                    Log.error(sqle.getMessage(), sqle);
                }
                finally {
                    DbConnectionManager.closeConnection(pstmt, con);
                }
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    public void removeAffiliationFromDB(MUCRoom room, JID jid,
            MUCRole.Affiliation oldAffiliation)
    {
    	final String bareJID = jid.toBareJID();
        if (room.isPersistent() && room.wasSavedToDB()) {
            if (MUCRole.Affiliation.member == oldAffiliation) {
                // Remove the user from the members table
                Connection con = null;
                PreparedStatement pstmt = null;
                try {
                    con = DbConnectionManager.getConnection();
                    pstmt = con.prepareStatement(DELETE_MEMBER);
                    pstmt.setLong(1, room.getID());
                    pstmt.setString(2, bareJID);
                    pstmt.executeUpdate();
                }
                catch (SQLException sqle) {
                    Log.error(sqle.getMessage(), sqle);
                }
                finally {
                    DbConnectionManager.closeConnection(pstmt, con);
                }
            }
            else {
                // Remove the user from the generic affiliations table
                Connection con = null;
                PreparedStatement pstmt = null;
                try {
                    con = DbConnectionManager.getConnection();
                    pstmt = con.prepareStatement(DELETE_AFFILIATION);
                    pstmt.setLong(1, room.getID());
                    pstmt.setString(2, bareJID);
                    pstmt.executeUpdate();
                }
                catch (SQLException sqle) {
                    Log.error(sqle.getMessage(), sqle);
                }
                finally {
                    DbConnectionManager.closeConnection(pstmt, con);
                }
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    public void removeAffiliationFromDB(JID bareJID)
    {
        Connection con = null;
        PreparedStatement pstmt = null;
        try {
            con = DbConnectionManager.getConnection();
            // Remove the user from the members table
            pstmt = con.prepareStatement(DELETE_USER_MEMBER);
            pstmt.setString(1, bareJID.toBareJID());
            pstmt.executeUpdate();
            DbConnectionManager.fastcloseStmt(pstmt);

            // Remove the user from the generic affiliations table
            pstmt = con.prepareStatement(DELETE_USER_MUCAFFILIATION);
            pstmt.setString(1, bareJID.toBareJID());
            pstmt.executeUpdate();
        }
        catch (SQLException sqle) {
            Log.error(sqle.getMessage(), sqle);
        }
        finally {
            DbConnectionManager.closeConnection(pstmt, con);
        }
    }

    /**
     * {@inheritDoc}
     */
    public boolean saveConversationLogEntry(ConversationLogEntry entry) {
        Connection con = null;
        PreparedStatement pstmt = null;
        try {
            con = DbConnectionManager.getConnection();
            pstmt = con.prepareStatement(ADD_CONVERSATION_LOG);
            pstmt.setLong(1, entry.getRoomID());
            pstmt.setString(2, entry.getSender().toString());
            pstmt.setString(3, entry.getNickname());
            pstmt.setString(4, StringUtils.dateToMillis(entry.getDate()));
            pstmt.setString(5, entry.getSubject());
            pstmt.setString(6, entry.getBody());
            pstmt.executeUpdate();
            return true;
        }
        catch (SQLException sqle) {
            Log.error("Error saving conversation log entry", sqle);
            return false;
        }
        finally {
            DbConnectionManager.closeConnection(pstmt, con);
        }
    }

    /**
     * Returns an integer based on the binary representation of the roles to broadcast.
     * 
     * @param room the room to marshall its roles to broadcast.
     * @return an integer based on the binary representation of the roles to broadcast.
     */
    private static int marshallRolesToBroadcast(MUCRoom room) {
        StringBuilder buffer = new StringBuilder();
        buffer.append((room.canBroadcastPresence("moderator") ? "1" : "0"));
        buffer.append((room.canBroadcastPresence("participant") ? "1" : "0"));
        buffer.append((room.canBroadcastPresence("visitor") ? "1" : "0"));
        return Integer.parseInt(buffer.toString(), 2);
    }

    /**
     * {@inheritDoc}
     */
    public String getProperty(String subdomain, String name) {    	
    	final MUCServiceProperties newProps = new MUCServiceProperties(subdomain);
    	final MUCServiceProperties oldProps = propertyMaps.putIfAbsent(subdomain, newProps);
    	if (oldProps != null) {
    		return oldProps.get(name);
    	} else {
    		return newProps.get(name);
    	}
    }

    /**
     * {@inheritDoc}
     */
    public String getProperty(String subdomain, String name, String defaultValue) {
    	final String value = getProperty(subdomain, name);
    	if (value != null) {
    		return value;
    	} else {
    		return defaultValue;
    	}
    }

    /**
     * {@inheritDoc}
     */
    public int getIntProperty(String subdomain, String name, int defaultValue) {
        String value = getProperty(subdomain, name);
        if (value != null) {
            try {
                return Integer.parseInt(value);
            }
            catch (NumberFormatException nfe) {
                // Ignore.
            }
        }
        return defaultValue;
    }

    /**
     * {@inheritDoc}
     */
    public long getLongProperty(String subdomain, String name, long defaultValue) {
        String value = getProperty(subdomain, name);
        if (value != null) {
            try {
                return Long.parseLong(value);
            }
            catch (NumberFormatException nfe) {
                // Ignore.
            }
        }
        return defaultValue;
    }

    /**
     * {@inheritDoc}
     */
    public boolean getBooleanProperty(String subdomain, String name) {
        return Boolean.valueOf(getProperty(subdomain, name));
    }

    /**
     * {@inheritDoc}
     */
    public boolean getBooleanProperty(String subdomain, String name, boolean defaultValue) {
        String value = getProperty(subdomain, name);
        if (value != null) {
            return Boolean.valueOf(value);
        }
        else {
            return defaultValue;
        }
    }

    /**
     * {@inheritDoc}
     */
    public List<String> getPropertyNames(String subdomain, String parent) {
    	MUCServiceProperties properties = new MUCServiceProperties(subdomain);
    	final MUCServiceProperties oldProps = propertyMaps.putIfAbsent(subdomain, properties);
    	if (oldProps != null) {
    		properties = oldProps;
    	} 
        return new ArrayList<String>(properties.getChildrenNames(parent));
    }

    /**
     * {@inheritDoc}
     */
    public List<String> getProperties(String subdomain, String parent) {
    	MUCServiceProperties properties = new MUCServiceProperties(subdomain);
    	final MUCServiceProperties oldProps = propertyMaps.putIfAbsent(subdomain, properties);
    	if (oldProps != null) {
    		properties = oldProps;
    	} 

        Collection<String> propertyNames = properties.getChildrenNames(parent);
        List<String> values = new ArrayList<String>();
        for (String propertyName : propertyNames) {
            String value = getProperty(subdomain, propertyName);
            if (value != null) {
                values.add(value);
            }
        }

        return values;
    }

    /**
     * {@inheritDoc}
     */
    public List<String> getPropertyNames(String subdomain) {
   	MUCServiceProperties properties = new MUCServiceProperties(subdomain);
    	final MUCServiceProperties oldProps = propertyMaps.putIfAbsent(subdomain, properties);
    	if (oldProps != null) {
    		properties = oldProps;
   	} 
        return new ArrayList<String>(properties.getPropertyNames());
    }

    /**
     * {@inheritDoc}
     */
    public void setProperty(String subdomain, String name, String value) {
        MUCServiceProperties properties = propertyMaps.get(subdomain);
        if (properties == null) {
            properties = new MUCServiceProperties(subdomain);
        }
        properties.put(name, value);
        propertyMaps.put(subdomain, properties);
    }

    /**
     * {@inheritDoc}
     */
    public void setLocalProperty(String subdomain, String name, String value) {
        MUCServiceProperties properties = propertyMaps.get(subdomain);
        if (properties == null) {
            properties = new MUCServiceProperties(subdomain);
        }
        properties.localPut(name, value);
        propertyMaps.put(subdomain, properties);
    }

    /**
     * {@inheritDoc}
     */
    public void setProperties(String subdomain, Map<String, String> propertyMap) {
        MUCServiceProperties properties = propertyMaps.get(subdomain);
        if (properties == null) {
            properties = new MUCServiceProperties(subdomain);
        }
        properties.putAll(propertyMap);
        propertyMaps.put(subdomain, properties);
    }

    /**
     * {@inheritDoc}
     */
    public void deleteProperty(String subdomain, String name) {
        MUCServiceProperties properties = propertyMaps.get(subdomain);
        if (properties == null) {
            properties = new MUCServiceProperties(subdomain);
        }
        properties.remove(name);
        propertyMaps.put(subdomain, properties);
    }

    /**
     * {@inheritDoc}
     */
    public void deleteLocalProperty(String subdomain, String name) {
        MUCServiceProperties properties = propertyMaps.get(subdomain);
        if (properties == null) {
            properties = new MUCServiceProperties(subdomain);
        }
        properties.localRemove(name);
        propertyMaps.put(subdomain, properties);
    }

    /**
     * {@inheritDoc}
     */
    public void refreshProperties(String subdomain) {
        propertyMaps.replace(subdomain, new MUCServiceProperties(subdomain));
    }
    

    // formerly in MUCServiceProperties
    /**
     * {@inheritDoc}
     */
    public void insertProperty(String name, String value, Long serviceID) {
        Connection con = null;
        PreparedStatement pstmt = null;
        try {
            con = DbConnectionManager.getConnection();
            pstmt = con.prepareStatement(INSERT_PROPERTY);
            pstmt.setLong(1, serviceID);
            pstmt.setString(2, name);
            pstmt.setString(3, value);
            pstmt.executeUpdate();
        }
        catch (SQLException e) {
            Log.error(e.getMessage(), e);
        }
        finally {
            DbConnectionManager.closeConnection(pstmt, con);
        }
    }

    /**
     * {@inheritDoc}
     */
    public void updateProperty(String name, String value, Long serviceID) {
        Connection con = null;
        PreparedStatement pstmt = null;
        try {
            con = DbConnectionManager.getConnection();
            pstmt = con.prepareStatement(UPDATE_PROPERTY);
            pstmt.setString(1, value);
            pstmt.setLong(2, serviceID);
            pstmt.setString(3, name);
            pstmt.executeUpdate();
        }
        catch (SQLException e) {
            Log.error(e.getMessage(), e);
        }
        finally {
            DbConnectionManager.closeConnection(pstmt, con);
        }
    }

    /**
     * {@inheritDoc}
     */
    public void deleteProperty(String name, Long serviceID) {
        Connection con = null;
        PreparedStatement pstmt = null;
        try {
            con = DbConnectionManager.getConnection();
            pstmt = con.prepareStatement(DELETE_PROPERTY);
            pstmt.setLong(1, serviceID);
            pstmt.setString(2, name);
            pstmt.executeUpdate();
        }
        catch (SQLException e) {
            Log.error(e.getMessage(), e);
        }
        finally {
            DbConnectionManager.closeConnection(pstmt, con);
        }
    }

    /**
     * {@inheritDoc}
     */
    public void loadProperties(Map<String, String> properties, Long serviceID) {
        Connection con = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        try {
            con = DbConnectionManager.getConnection();
            pstmt = con.prepareStatement(LOAD_PROPERTIES);
            pstmt.setLong(1, serviceID);
            rs = pstmt.executeQuery();
            while (rs.next()) {
                String name = rs.getString(1);
                String value = rs.getString(2);
                properties.put(name, value);
            }
        }
        catch (Exception e) {
            Log.error(e.getMessage(), e);
        }
        finally {
            DbConnectionManager.closeConnection(rs, pstmt, con);
        }
    }
}