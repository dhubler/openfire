/**
 * $RCSfile: RoutingTableImpl.java,v $
 * $Revision: 3138 $
 * $Date: 2005-12-01 02:13:26 -0300 (Thu, 01 Dec 2005) $
 *
 * Copyright (C) 2007 Jive Software. All rights reserved.
 *
 * This software is published under the terms of the GNU Public License (GPL),
 * a copy of which is included in this distribution.
 */

package org.jivesoftware.openfire.spi;

import org.jivesoftware.openfire.*;
import org.jivesoftware.openfire.auth.UnauthorizedException;
import org.jivesoftware.openfire.container.BasicModule;
import org.jivesoftware.openfire.server.OutgoingSessionPromise;
import org.jivesoftware.openfire.session.*;
import org.jivesoftware.util.JiveGlobals;
import org.jivesoftware.util.Log;
import org.jivesoftware.util.cache.Cache;
import org.jivesoftware.util.cache.CacheFactory;
import org.jivesoftware.util.lock.LockManager;
import org.xmpp.packet.*;

import java.util.*;
import java.util.concurrent.locks.Lock;

/**
 * Routing table that stores routes to client sessions, outgoing server sessions
 * and components. As soon as a user authenticates with the server its client session
 * will be added to the routing table. Whenever the client session becomes available
 * or unavailable the routing table will be updated too.<p>
 *
 * When running inside of a cluster the routing table will also keep references to routes
 * hosted in other cluster nodes. A {@link RemotePacketRouter} will be use to route packets
 * to routes hosted in other cluster nodes.<p>
 *
 * Failure to route a packet will end up sending {@link IQRouter#routingFailed(JID, Packet)},
 * {@link MessageRouter#routingFailed(JID, Packet)} or {@link PresenceRouter#routingFailed(JID, Packet)}
 * depending on the packet type that tried to be sent.
 *
 * @author Gaston Dombiak
 */
public class RoutingTableImpl extends BasicModule implements RoutingTable {

    /**
     * Cache (unlimited, never expire) that holds outgoing sessions to remote servers from this server.
     * Key: server domain, Value: nodeID
     */
    private Cache<String, byte[]> serversCache;
    /**
     * Cache (unlimited, never expire) that holds sessions of external components connected to the server.
     * Key: component domain, Value: nodeID
     */
    private Cache<String, byte[]> componentsCache;
    /**
     * Cache (unlimited, never expire) that holds sessions of user that have authenticated with the server.
     * Key: full JID, Value: {nodeID, available/unavailable}
     */
    private Cache<String, ClientRoute> usersCache;
    /**
     * Cache (unlimited, never expire) that holds sessions of anoymous user that have authenticated with the server.
     * Key: full JID, Value: {nodeID, available/unavailable}
     */
    private Cache<String, ClientRoute> anonymousUsersCache;
    /**
     * Cache (unlimited, never expire) that holds list of connected resources of authenticated users
     * (includes anonymous).
     * Key: bare JID, Value: list of full JIDs of the user
     */
    private Cache<String, List<String>> usersSessions;

    private String serverName;
    private XMPPServer server;
    private LocalRoutingTable localRoutingTable;
    private RemotePacketRouter remotePacketRouter;
    private IQRouter iqRouter;
    private MessageRouter messageRouter;
    private PresenceRouter presenceRouter;

    public RoutingTableImpl() {
        super("Routing table");
        serversCache = CacheFactory.createCache("Routing Servers Cache");
        componentsCache = CacheFactory.createCache("Routing Components Cache");
        usersCache = CacheFactory.createCache("Routing Users Cache");
        anonymousUsersCache = CacheFactory.createCache("Routing AnonymousUsers Cache");
        usersSessions = CacheFactory.createCache("Routing User Sessions");
        localRoutingTable = new LocalRoutingTable();
    }

    public void addServerRoute(JID route, LocalOutgoingServerSession destination) {
        String address = destination.getAddress().getDomain();
        localRoutingTable.addRoute(address, destination);
        serversCache.put(address, server.getNodeID());
    }

    public void addComponentRoute(JID route, RoutableChannelHandler destination) {
        String address = destination.getAddress().getDomain();
        localRoutingTable.addRoute(address, destination);
        componentsCache.put(address, server.getNodeID());
    }

    public void addClientRoute(JID route, LocalClientSession destination) {
        String address = destination.getAddress().toString();
        boolean available = destination.getPresence().isAvailable();
        localRoutingTable.addRoute(address, destination);
        if (destination.getAuthToken().isAnonymous()) {
            anonymousUsersCache.put(address, new ClientRoute(server.getNodeID(), available));
            // Add the session to the list of user sessions
            if (route.getResource() != null && !available) {
                Lock lock = LockManager.getLock(route.toBareJID());
                try {
                    lock.lock();
                    usersSessions.put(route.toBareJID(), Arrays.asList(route.toString()));
                }
                finally {
                    lock.unlock();
                }
            }
        }
        else {
            usersCache.put(address, new ClientRoute(server.getNodeID(), available));
            // Add the session to the list of user sessions
            if (route.getResource() != null && !available) {
                Lock lock = LockManager.getLock(route.toBareJID());
                try {
                    lock.lock();
                    List<String> jids = usersSessions.get(route.toBareJID());
                    if (jids == null) {
                        jids = new ArrayList<String>();
                    }
                    jids.add(route.toString());
                    usersSessions.put(route.toBareJID(), jids);
                }
                finally {
                    lock.unlock();
                }
            }
        }
    }

    public void broadcastPacket(Message packet, boolean onlyLocal) {
        // Send the message to client sessions connected to this JVM
        for(ClientSession session : localRoutingTable.getClientRoutes()) {
            session.process(packet);
        }

        // Check if we need to broadcast the message to client sessions connected to remote cluter nodes
        if (!onlyLocal && remotePacketRouter != null) {
            remotePacketRouter.broadcastPacket(packet);
        }
    }

    public void routePacket(JID jid, Packet packet) throws PacketException {
        boolean routed = false;
        JID address = packet.getTo();
        if (address == null) {
            throw new PacketException("To address cannot be null.");
        }

        if (serverName.equals(jid.getDomain())) {
            if (jid.getResource() == null) {
                // Packet sent to a bare JID of a user
                if (packet instanceof Message) {
                    // Find best route of local user
                    routed = routeToBareJID(jid, (Message) packet);
                }
                else {
                    throw new PacketException("Cannot route packet of type IQ or Presence to bare JID: " + packet);
                }
            }
            else {
                // Packet sent to local user (full JID)
                boolean onlyAvailable = true;
                if (packet instanceof IQ) {
                    onlyAvailable = packet.getFrom() != null;
                }
                else if (packet instanceof Message) {
                    onlyAvailable = true;
                }
                else if (packet instanceof Presence) {
                    onlyAvailable = true;
                }
                ClientRoute clientRoute = usersCache.get(jid.toString());
                if (clientRoute == null) {
                    clientRoute = anonymousUsersCache.get(jid.toString());
                }
                if (clientRoute != null) {
                    if (onlyAvailable && !clientRoute.isAvailable()) {
                        // Packet should only be sent to available sessions and the route is not available
                        routed = false;
                    }
                    else {
                        if (clientRoute.getNodeID() == server.getNodeID()) {
                            // This is a route to a local user hosted in this node
                            try {
                                localRoutingTable.getRoute(jid.toString()).process(packet);
                                routed = true;
                            } catch (UnauthorizedException e) {
                                Log.error(e);
                            }
                        }
                        else {
                            // This is a route to a local user hosted in other node
                            if (remotePacketRouter != null) {
                                routed = remotePacketRouter.routePacket(clientRoute.getNodeID(), jid, packet);
                            }
                        }
                    }
                }
            }
        }
        else if (jid.getDomain().contains(serverName)) {
            // Packet sent to component hosted in this server
            byte[] nodeID = componentsCache.get(jid.getDomain());
            if (nodeID != null) {
                if (nodeID == server.getNodeID()) {
                    // This is a route to a local component hosted in this node
                    try {
                        localRoutingTable.getRoute(jid.getDomain()).process(packet);
                        routed = true;
                    } catch (UnauthorizedException e) {
                        Log.error(e);
                    }
                }
                else {
                    // This is a route to a local component hosted in other node
                    if (remotePacketRouter != null) {
                        routed = remotePacketRouter.routePacket(nodeID, jid, packet);
                    }
                }
            }
        }
        else {
            // Packet sent to remote server
            byte[] nodeID = serversCache.get(jid.getDomain());
            if (nodeID != null) {
                if (nodeID == server.getNodeID()) {
                    // This is a route to a remote server connected from this node
                    try {
                        localRoutingTable.getRoute(jid.getDomain()).process(packet);
                        routed = true;
                    } catch (UnauthorizedException e) {
                        Log.error(e);
                    }
                }
                else {
                    // This is a route to a remote server connected from other node
                    if (remotePacketRouter != null) {
                        routed = remotePacketRouter.routePacket(nodeID, jid, packet);
                    }
                }
            }
            else {
                // Return a promise of a remote session. This object will queue packets pending
                // to be sent to remote servers
                // TODO Make sure that creating outgoing connections is thread-safe across cluster nodes 
                OutgoingSessionPromise.getInstance().process(packet);
                routed = true;
            }
        }

        if (!routed) {
            if (Log.isDebugEnabled()) {
                Log.debug("Failed to route packet to JID: " + jid + " packet: " + packet);
            }
            if (packet instanceof IQ) {
                iqRouter.routingFailed(jid, packet);
            }
            else if (packet instanceof Message) {
                messageRouter.routingFailed(jid, packet);
            }
            else if (packet instanceof Presence) {
                presenceRouter.routingFailed(jid, packet);
            }
        }
    }

    /**
     * Deliver the message sent to the bare JID of a local user to the best connected resource. If the
     * target user is not online then messages will be stored offline according to the offline strategy.
     * However, if the user is connected from only one resource then the message will be delivered to
     * that resource. In the case that the user is connected from many resources the logic will be the
     * following:
     * <ol>
     *  <li>Select resources with highest priority</li>
     *  <li>Select resources with highest show value (chat, available, away, xa, dnd)</li>
     *  <li>Select resource with most recent activity</li>
     * </ol>
     *
     * Admins can override the above logic and just send the message to all connected resources
     * with highest priority by setting the system property <tt>route.all-resources</tt> to
     * <tt>true</tt>.
     *
     * @param recipientJID the bare JID of the target local user.
     * @param packet the message to send.
     * @return true if at least one target session was found
     */
    private boolean routeToBareJID(JID recipientJID, Message packet) {
        List<ClientSession> sessions = new ArrayList<ClientSession>();
        // Get existing AVAILABLE sessions of this user
        for (JID address : getRoutes(recipientJID)) {
            ClientSession session = getClientRoute(address);
            if (session != null) {
                sessions.add(session);
            }
        }
        sessions = getHighestPrioritySessions(sessions);
        if (sessions.isEmpty()) {
            // No session is available so store offline
            return false;
        }
        else if (sessions.size() == 1) {
            // Found only one session so deliver message
            sessions.get(0).process(packet);
        }
        else {
            // Many sessions have the highest priority (be smart now) :)
            if (!JiveGlobals.getBooleanProperty("route.all-resources", false)) {
                // Sort sessions by show value (e.g. away, xa)
                Collections.sort(sessions, new Comparator<ClientSession>() {

                    public int compare(ClientSession o1, ClientSession o2) {
                        int thisVal = getShowValue(o1);
                        int anotherVal = getShowValue(o2);
                        return (thisVal<anotherVal ? -1 : (thisVal==anotherVal ? 0 : 1));
                    }

                    /**
                     * Priorities are: chat, available, away, xa, dnd.
                     */
                    private int getShowValue(ClientSession session) {
                        Presence.Show show = session.getPresence().getShow();
                        if (show == Presence.Show.chat) {
                            return 1;
                        }
                        else if (show == null) {
                            return 2;
                        }
                        else if (show == Presence.Show.away) {
                            return 3;
                        }
                        else if (show == Presence.Show.xa) {
                            return 4;
                        }
                        else {
                            return 5;
                        }
                    }
                });

                // Get same sessions with same max show value
                List<ClientSession> targets = new ArrayList<ClientSession>();
                Presence.Show showFilter = sessions.get(0).getPresence().getShow();
                for (ClientSession session : sessions) {
                    if (session.getPresence().getShow() == showFilter) {
                        targets.add(session);
                    }
                    else {
                        break;
                    }
                }

                // Get session with most recent activity (and highest show value)
                Collections.sort(targets, new Comparator<ClientSession>() {
                    public int compare(ClientSession o1, ClientSession o2) {
                        return o1.getLastActiveDate().compareTo(o2.getLastActiveDate());
                    }
                });
                // Deliver stanza to session with highest priority, highest show value and most recent activity
                targets.get(0).process(packet);
            }
            else {
                // Deliver stanza to all connected resources with highest priority
                for (ClientSession session : sessions) {
                    session.process(packet);
                }
            }
        }
        return true;
    }

    /**
     * Returns the sessions that had the highest presence priority greater than zero.
     *
     * @param sessions the list of user sessions that filter and get the ones with highest priority.
     * @return the sessions that had the highest presence priority greater than zero or empty collection
     *         if all were negative.
     */
    private List<ClientSession> getHighestPrioritySessions(List<ClientSession> sessions) {
        int highest = Integer.MIN_VALUE;
        // Get the highest priority amongst the sessions
        for (ClientSession session : sessions) {
            int priority = session.getPresence().getPriority();
            if (priority >= 0 && priority > highest) {
                highest = priority;
            }
        }
        // Answer an empty collection if all have negative priority
        if (highest == Integer.MIN_VALUE) {
            return Collections.emptyList();
        }
        // Get sessions that have the highest priority
        List<ClientSession> answer = new ArrayList<ClientSession>(sessions.size());
        for (ClientSession session : sessions) {
            if (session.getPresence().getPriority() == highest) {
                answer.add(session);
            }
        }
        return answer;
    }

    public ClientSession getClientRoute(JID jid) {
        // Check if this session is hosted by this cluster node
        ClientSession session = (ClientSession) localRoutingTable.getRoute(jid.toString());
        if (session == null) {
            // The session is not in this JVM so assume remote
            RemoteSessionLocator locator = server.getRemoteSessionLocator();
            if (locator != null) {
                // Check if the session is hosted by other cluster node
                ClientRoute route = usersCache.get(jid.toString());
                if (route == null) {
                    route = anonymousUsersCache.get(jid.toString());
                }
                if (route != null) {
                    session = locator.getClientSession(route.getNodeID(), jid);
                }
            }
        }
        return session;
    }

    public Collection<ClientSession> getClientsRoutes() {
        // Add sessions hosted by this cluster node
        Collection<ClientSession> sessions = new ArrayList<ClientSession>(localRoutingTable.getClientRoutes());
        // Add sessions not hosted by this JVM
        RemoteSessionLocator locator = server.getRemoteSessionLocator();
        if (locator != null) {
            // Add sessions of non-anonymous users hosted by other cluster nodes
            for (Map.Entry<String, ClientRoute> entry : usersCache.entrySet()) {
                ClientRoute route = entry.getValue();
                if (route.getNodeID() != server.getNodeID()) {
                    sessions.add(locator.getClientSession(route.getNodeID(), new JID(entry.getKey())));
                }
            }
            // Add sessions of anonymous users hosted by other cluster nodes
            for (Map.Entry<String, ClientRoute> entry : anonymousUsersCache.entrySet()) {
                ClientRoute route = entry.getValue();
                if (route.getNodeID() != server.getNodeID()) {
                    sessions.add(locator.getClientSession(route.getNodeID(), new JID(entry.getKey())));
                }
            }
        }
        return sessions;
    }

    public OutgoingServerSession getServerRoute(JID jid) {
        // Check if this session is hosted by this cluster node
        OutgoingServerSession session = (OutgoingServerSession) localRoutingTable.getRoute(jid.getDomain());
        if (session == null) {
            // The session is not in this JVM so assume remote
            RemoteSessionLocator locator = server.getRemoteSessionLocator();
            if (locator != null) {
                // Check if the session is hosted by other cluster node
                byte[] nodeID = serversCache.get(jid.getDomain());
                if (nodeID != null) {
                    session = locator.getOutgoingServerSession(nodeID, jid);
                }
            }
        }
        return session;
    }

    public Collection<String> getServerHostnames() {
        return serversCache.keySet();
    }

    public boolean hasClientRoute(JID jid) {
        return usersCache.get(jid.toString()) != null || isAnonymousRoute(jid);
    }

    public boolean isAnonymousRoute(JID jid) {
        return anonymousUsersCache.get(jid.toString()) != null;
    }

    public boolean hasServerRoute(JID jid) {
        return serversCache.get(jid.getDomain()) != null;
    }

    public boolean hasComponentRoute(JID jid) {
        return componentsCache.get(jid.getDomain()) != null;
    }

    public List<JID> getRoutes(JID route) {
        // TODO Refactor API to be able to get c2s sessions available only/all
        List<JID> jids = new ArrayList<JID>();
        if (serverName.equals(route.getDomain())) {
            // Address belongs to local user
            if (route.getResource() != null) {
                // Address is a full JID of a user
                ClientRoute clientRoute = usersCache.get(route.toString());
                if (clientRoute == null) {
                    clientRoute = anonymousUsersCache.get(route.toString());
                }
                if (clientRoute != null && clientRoute.isAvailable()) {
                    jids.add(route);
                }
            }
            else {
                // Address is a bare JID so return all AVAILABLE resources of user
                List<String> sessions = usersSessions.get(route.toBareJID());
                if (sessions != null) {
                    // Select only available sessions
                    for (String jid : sessions) {
                        ClientRoute clientRoute = usersCache.get(jid);
                        if (clientRoute == null) {
                            clientRoute = anonymousUsersCache.get(jid);
                        }
                        if (clientRoute != null && clientRoute.isAvailable()) {
                            jids.add(new JID(jid));
                        }
                    }
                }
            }
        }
        else if (route.getDomain().contains(serverName)) {
            // Packet sent to component hosted in this server
            byte[] nodeID = componentsCache.get(route.getDomain());
            if (nodeID != null) {
                jids.add(route);
            }
        }
        else {
            // Packet sent to remote server
            jids.add(route);
        }
        return jids;
    }

    public boolean removeClientRoute(JID route) {
        boolean anonymous = false;
        String address = route.toString();
        ClientRoute clientRoute = usersCache.remove(address);
        if (clientRoute == null) {
            clientRoute = anonymousUsersCache.remove(address);
            anonymous = true;
        }
        if (clientRoute != null && route.getResource() != null) {
            Lock lock = LockManager.getLock(route.toBareJID());
            try {
                lock.lock();
                if (anonymous) {
                    usersSessions.remove(route.toBareJID());
                }
                else {
                    List<String> jids = usersSessions.get(route.toBareJID());
                    if (jids != null) {
                        jids.remove(route.toString());
                        if (!jids.isEmpty()) {
                            usersSessions.put(route.toBareJID(), jids);
                        }
                        else {
                            usersSessions.remove(route.toBareJID());
                        }
                    }
                }
            }
            finally {
                lock.unlock();
            }
        }
        localRoutingTable.removeRoute(address);
        return clientRoute != null;
    }

    public boolean removeServerRoute(JID route) {
        String address = route.getDomain();
        boolean removed = serversCache.remove(address) != null;
        localRoutingTable.removeRoute(address);
        return removed;
    }

    public boolean removeComponentRoute(JID route) {
        String address = route.getDomain();
        boolean removed = componentsCache.remove(address) != null;
        localRoutingTable.removeRoute(address);
        return removed;
    }

    public void setRemotePacketRouter(RemotePacketRouter remotePacketRouter) {
        this.remotePacketRouter = remotePacketRouter;
    }

    public void initialize(XMPPServer server) {
        super.initialize(server);
        this.server = server;
        serverName = server.getServerInfo().getName();
        iqRouter = server.getIQRouter();
        messageRouter = server.getMessageRouter();
        presenceRouter = server.getPresenceRouter();
    }

    public void start() throws IllegalStateException {
        super.start();
        localRoutingTable.start();
    }

    public void stop() {
        super.stop();
        localRoutingTable.stop();
    }
}
