<%--
  -	$RCSfile$
  -	$Revision$
  -	$Date$
--%>
<%@ taglib uri="core" prefix="c"%>
<%@ page import="org.jivesoftware.util.*,
                 java.util.*,
                 org.jivesoftware.messenger.*,
                 java.text.DateFormat,
                 java.text.NumberFormat,
                 org.jivesoftware.admin.*,
                 org.jivesoftware.messenger.user.User" %>

<jsp:useBean id="webManager" class="org.jivesoftware.util.WebManager" />


<%  // Get parameters
    String jid = ParamUtils.getParameter(request, "jid");

    // Handle a "go back" click:
    if (request.getParameter("back") != null) {
        response.sendRedirect("session-summary.jsp");
        return;
    }

    // Get the session & address objects
    SessionManager sessionManager = webManager.getSessionManager();
    XMPPAddress address = XMPPAddress.parseJID(jid);
    Session currentSess = sessionManager.getSession(address);
    boolean isAnonymous = address.getName() == null || "".equals(address.getName());

    // Get a presence manager
    PresenceManager presenceManager = webManager.getPresenceManager();

    // Get user object
    User user = null;
    if (!isAnonymous) {
        user = webManager.getUserManager().getUser(address.getName());
    }

    // Handle a "message" click:
    if (request.getParameter("message") != null) {
        response.sendRedirect("user-message.jsp?username=" + user.getUsername());
        return;
    }

    // See if there are multiple sessions for this user:
    Collection<Session> sessions = null;
    int sessionCount = sessionManager.getSessionCount(address.getName());
    if (!isAnonymous && sessionCount > 1) {
        sessions = sessionManager.getSessions(address.getName());
    }

    // Date dateFormatter for all dates on this page:
    DateFormat dateFormatter = DateFormat.getDateTimeInstance(DateFormat.MEDIUM,DateFormat.SHORT);

    // Number dateFormatter for all numbers on this page:
    NumberFormat numFormatter = NumberFormat.getNumberInstance();
%>

<jsp:useBean id="pageinfo" scope="request" class="org.jivesoftware.admin.AdminPageBean" />
<%  // Title of this page and breadcrumbs
    String title = "Session Details";
    pageinfo.setTitle(title);
    pageinfo.getBreadcrumbs().add(new AdminPageBean.Breadcrumb("Main", "main.jsp"));
    pageinfo.getBreadcrumbs().add(new AdminPageBean.Breadcrumb(title, "session-details.jsp"));
    pageinfo.setSubPageID("session-details");
%>
<%@ include file="top.jsp" %>
<jsp:include page="title.jsp" flush="true" />


<p>
Below are session details for the session <b><%= address.toString() %></b>. If the
user <b><%= address.getName() %></b> has multiple sessions open, they will appear below.
</p>

<p>
<b>Session Details</b>
</p>

<div class="jive-table">
<table cellpadding="3" cellspacing="1" border="0" width="100%">
<tr>
    <td class="jive-label">
        Session ID:
    </td>
    <td>
        <%= address.toString() %>
    </td>
</tr>
<tr>
    <td class="jive-label">
        User Name &amp; Resource:
    </td>
    <td>
        <%  String n = address.getName(); %>
        <%  if (n == null || "".equals(n)) { %>

            <i>Anonymous</i> - <%= address.getResource() %>

        <%  } else { %>

            <a href="user-properties.jsp?username=<%= n %>"><%= n %></a>
            - <%= address.getResource() %>

        <%  } %>
    </td>
</tr>
<tr>
    <td class="jive-label">
        Status:
    </td>
    <td>
        <%
            int status = currentSess.getStatus();
            if (status == Session.STATUS_CLOSED) {
        %>
            Closed

        <%
            } else if (status == Session.STATUS_CONNECTED) {
        %>

            Connected

        <%
            } else if (status == Session.STATUS_STREAMING) {
        %>

            Streaming

        <%
            } else if (status == Session.STATUS_AUTHENTICATED) {
        %>

            Authenticated

        <%
            } else {
        %>

            Unknown

        <%
            }
        %>
    </td>
</tr>
<tr>
    <td class="jive-label">
        Presence:
    </td>
    <td>
        <%
            int show = currentSess.getPresence().getShow();
            if (show == Presence.SHOW_AWAY) {
        %>

            <img src="images/bullet-yellow-14x14.gif" width="14" height="14" border="0" title="Away">
            Away - <%= currentSess.getPresence().getStatus() %>

        <%
            } else if (show == Presence.SHOW_CHAT) {
        %>

            <img src="images/bullet-green-14x14.gif" width="14" height="14" border="0" title="Available to Chat">
            Available to Chat
        <%
            } else if (show == Presence.SHOW_DND) {
        %>

            <img src="images/bullet-red-14x14.gif" width="14" height="14" border="0" title="Do not Disturb">
            Do Not Disturb - <%= currentSess.getPresence().getStatus() %>

        <%
            } else if (show == Presence.SHOW_NONE) {
        %>

            <img src="images/bullet-green-14x14.gif" width="14" height="14" border="0" title="Online">
            Online

        <%
            } else if (show == Presence.SHOW_XA) {
        %>

            <img src="images/bullet-red-14x14.gif" width="14" height="14" border="0" title="Extended Away">
            Extended Away - <%= currentSess.getPresence().getStatus() %>

        <%
            } else {
        %>

            Unknown/Not Recognized

        <%
            }
        %>
    </td>
</tr>
<tr>
    <td class="jive-label">
        Session Created:
    </td>
    <td>
        <%= dateFormatter.format(currentSess.getCreationDate()) %>
    </td>
</tr>
<tr>
    <td class="jive-label">
        Session Last Active:
    </td>
    <td>
        <%= dateFormatter.format(currentSess.getLastActiveDate()) %>
    </td>
</tr>
<tr>
    <td class="jive-label">
        Session Statistics:
    </td>
    <td>
        Packets Received/Sent:
        <%= numFormatter.format(currentSess.getNumClientPackets()) %>/<%= numFormatter.format(currentSess.getNumServerPackets()) %>
    </td>
</tr>
<tr>
    <td class="jive-label">
        Client IP / Hostname:
    </td>
    <td>
        <%= currentSess.getConnection().getInetAddress().getHostAddress() %>
        /
        <%= currentSess.getConnection().getInetAddress().getHostName() %>
    </td>
</tr>
</table>
</div>

<%  // Show a list of multiple user sessions if there is more than 1 session:
    if (sessionCount > 1) {
%>
    <p>
    <b>Multiple User Sessions</b>
    </p>

    <div class="jive-table">
    <table cellpadding="3" cellspacing="1" border="0" width="100%">
    <tr>
        <th>&nbsp;</th>
        <th>Name</th>
        <th>Resource</th>
        <th>Status</th>
        <th nowrap colspan="2">Presence (if authenticated)</th>
        <th nowrap>Client IP</th>
        <th nowrap>Close Connection</th>
    </tr>

    <%  int count = 0;
        String linkURL = "session-details.jsp";
        for (Session sess : sessions) {
            count++;
            boolean current = sess.getAddress().equals(address);
    %>
        <%@ include file="session-row.jspf" %>

    <%  } %>

    </table>
    </div>

    <br>

    <table cellpadding="0" cellspacing="0" border="0" width="100%">
    <tr>
        <td width="1%" nowrap>

            <div class="jive-table">
            <table cellpadding="0" cellspacing="0" border="0">
            <tr class="jive-current"><td><img src="images/blank.gif" width="12" height="12" border="0"></td></tr>
            </table>
            </div>

        </td>
        <td width="99%">

            &nbsp; = Current session details above.

        </td>
    </tr>
    </table>

<%  } %>

<br>

<form action="session-details.jsp">
<input type="hidden" name="jid" value="<%= jid %>">
<center>
<%  if (!isAnonymous && presenceManager.isAvailable(user)) { %>

    <input type="submit" name="message" value="Message this Session">

<%  } %>
<input type="submit" name="back" value="Back to Summary">
</center>
</form>

<%@ include file="footer.jsp" %>
