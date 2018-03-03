/*******************************************************************************
 * Copyright (c) 2015 Composent, Inc. and others. All rights reserved. This
 * program and the accompanying materials are made available under the terms of
 * the Eclipse Public License v1.0 which accompanies this distribution, and is
 * available at http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors: Composent, Inc. - initial API and implementation
 ******************************************************************************/
package org.eclipse.ecf.internal.provider.xmpp.smack;

import java.io.IOException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.Iterator;
import java.util.Map;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLSession;

import org.eclipse.core.runtime.IAdapterManager;
import org.eclipse.ecf.core.ContainerConnectException;
import org.eclipse.ecf.core.identity.ID;
import org.eclipse.ecf.core.identity.IDFactory;
import org.eclipse.ecf.core.identity.Namespace;
import org.eclipse.ecf.core.security.CallbackHandler;
import org.eclipse.ecf.core.util.ECFException;
import org.eclipse.ecf.internal.provider.xmpp.XmppPlugin;
import org.eclipse.ecf.provider.comm.DisconnectEvent;
import org.eclipse.ecf.provider.comm.IAsynchEventHandler;
import org.eclipse.ecf.provider.comm.IConnectionListener;
import org.eclipse.ecf.provider.comm.ISynchAsynchConnection;
import org.eclipse.ecf.provider.xmpp.identity.XMPPID;
import org.eclipse.ecf.provider.xmpp.identity.XMPPRoomID;
import org.jivesoftware.smack.ConnectionListener;
import org.jivesoftware.smack.SmackConfiguration;
import org.jivesoftware.smack.SmackException;
import org.jivesoftware.smack.SmackException.NoResponseException;
import org.jivesoftware.smack.SmackException.NotConnectedException;
import org.jivesoftware.smack.SmackException.NotLoggedInException;
import org.jivesoftware.smack.StanzaListener;
import org.jivesoftware.smack.XMPPConnection;
import org.jivesoftware.smack.XMPPException;
import org.jivesoftware.smack.chat.Chat;
import org.jivesoftware.smack.chat.ChatManager;
import org.jivesoftware.smack.chat.ChatMessageListener;
import org.jivesoftware.smack.packet.Bind;
import org.jivesoftware.smack.packet.IQ;
import org.jivesoftware.smack.packet.Message;
import org.jivesoftware.smack.packet.Message.Type;
import org.jivesoftware.smack.packet.Presence;
import org.jivesoftware.smack.packet.Stanza;
import org.jivesoftware.smack.roster.Roster;
import org.jivesoftware.smack.roster.RosterEntry;
import org.jivesoftware.smack.tcp.XMPPTCPConnection;
import org.jivesoftware.smack.tcp.XMPPTCPConnectionConfiguration;
import org.jivesoftware.smack.util.TLSUtils;
import org.jivesoftware.smackx.jiveproperties.JivePropertiesManager;

public class ECFConnection implements ISynchAsynchConnection {

	private static final boolean ACCEPT_ALL_CERTIFICATES = new Boolean(System.getProperty("org.eclipse.ecf.provider.xmpp.smack.acceptAllCertificates","false")).booleanValue();
	
	private static final String GOOGLE_TALK_HOST = "talk.google.com";
	public static final String CLIENT_TYPE = "ecf.";
	public static final boolean DEBUG = Boolean.getBoolean("smack.debug");

	protected static final String STRING_ENCODING = "UTF-8";
	public static final String OBJECT_PROPERTY_NAME = ECFConnection.class.getName() + ".object";
	protected static final int XMPP_DEFAULT_PORT = 5222;
	protected static final int XMPPS_DEFAULT_PORT = 5223;

	private XMPPTCPConnection connection = null;
	private IAsynchEventHandler handler = null;
	private boolean isStarted = false;
	private int serverPort = -1;
	private String serverResource;
	@SuppressWarnings("rawtypes")
	private final Map properties = null;
	private boolean isConnected = false;
	private Namespace namespace = null;

	private boolean google = false;

	private boolean disconnecting = false;

	private int BIND_TIMEOUT = new Integer(
			System.getProperty("org.eclipse.ecf.provider.xmpp.ECFConnection.bindTimeout", "15000")).intValue();

	private Object bindLock = new Object();

	private String jid;

	private CallbackHandler callbackHandler;

	private final StanzaListener packetListener = new StanzaListener() {
		@Override
		public void processPacket(Stanza packet) throws NotConnectedException {
			handlePacket(packet);
		}
	};

	private final ConnectionListener connectionListener = new ConnectionListener() {
		public void connectionClosed() {
			handleConnectionClosed(new IOException("Connection reset by peer"));
		}

		public void connectionClosedOnError(Exception e) {
			handleConnectionClosed(e);
		}

		public void reconnectingIn(int seconds) {
		}

		public void reconnectionFailed(Exception e) {
		}

		public void reconnectionSuccessful() {
		}

		@Override
		public void connected(XMPPConnection connection) {
		}

		@Override
		public void authenticated(XMPPConnection connection, boolean resumed) {
		}
	};

	protected void logException(String msg, Throwable t) {
		XmppPlugin.log(msg, t);
	}

	@SuppressWarnings("rawtypes")
	public Map getProperties() {
		return properties;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.core.runtime.IAdaptable#getAdapter(java.lang.Class)
	 */
	@SuppressWarnings("unchecked")
	public Object getAdapter(@SuppressWarnings("rawtypes") Class adapter) {
		if (adapter == null)
			return null;
		if (adapter.isInstance(this))
			return this;
		final IAdapterManager adapterManager = XmppPlugin.getDefault().getAdapterManager();
		return (adapterManager == null) ? null : adapterManager.loadAdapter(this, adapter.getName());
	}

	public XMPPConnection getXMPPConnection() {
		return connection;
	}

	public ECFConnection(boolean google, Namespace ns, IAsynchEventHandler h) {
		this(google, ns, h, null);
	}

	public ECFConnection(boolean google, Namespace ns, IAsynchEventHandler h, CallbackHandler ch) {
		this.handler = h;
		this.namespace = ns;
		this.google = google;
		this.callbackHandler = ch;
	}

	protected String getPasswordForObject(Object data) {
		String password = null;
		try {
			password = (String) data;
		} catch (final ClassCastException e) {
			return null;
		}
		return password;
	}

	private XMPPID getXMPPID(ID remote) throws ECFException {
		XMPPID jabberID = null;
		try {
			jabberID = (XMPPID) remote;
		} catch (final ClassCastException e) {
			throw new ECFException(e);
		}
		return jabberID;
	}

	public synchronized Object connect(ID remote, Object data, int timeout) throws ECFException {
		if (connection != null)
			throw new ECFException("already connected");
		if (timeout > 0)
			SmackConfiguration.setDefaultPacketReplyTimeout(timeout);
		Roster.setDefaultSubscriptionMode(Roster.SubscriptionMode.manual);

		final XMPPID jabberURI = getXMPPID(remote);

		String username = jabberURI.getNodename();
		String hostname = jabberURI.getHostname();
		String hostnameOverride = null;

		// Check for the URI form of "joe@bloggs.org;talk.google.com", which
		// would at this point would have
		// - username = "joe"
		// - hostname = "blogs.org;talk.google.com"
		// - hostnameOverride = null
		//
		// We need to turn this into:
		// - username = "joe"
		// - hostname = "bloggs.org"
		// - hostnameOverride = "talk.google.com"

		int semiColonIdx = hostname.lastIndexOf(';');
		if (semiColonIdx != -1) {
			hostnameOverride = hostname.substring(semiColonIdx + 1);
			hostname = hostname.substring(0, semiColonIdx);
		}

		if (google && hostnameOverride == null) {
			hostnameOverride = GOOGLE_TALK_HOST;
		}
		final String serviceName = hostname;

		serverPort = jabberURI.getPort();
		serverResource = jabberURI.getResourceName();
		if (serverResource == null || serverResource.equals(XMPPID.PATH_DELIMITER)) {
			serverResource = getClientIdentifier();
			jabberURI.setResourceName(serverResource);
		}
		try {
			XMPPTCPConnectionConfiguration config;
			javax.security.auth.callback.CallbackHandler cbh = (javax.security.auth.callback.CallbackHandler) ((callbackHandler instanceof javax.security.auth.callback.CallbackHandler)
					? callbackHandler : null);

			XMPPTCPConnectionConfiguration.Builder builder = XMPPTCPConnectionConfiguration.builder();
			if (hostnameOverride != null) {
				builder.setHost(hostnameOverride).setServiceName(serviceName).setCallbackHandler(cbh);
			} else if (serverPort == -1) {
				builder.setServiceName(serviceName).setCallbackHandler(cbh);
			} else {
				builder.setServiceName(serviceName).setPort(serverPort).setCallbackHandler(cbh);
			}

			if (DEBUG)
				builder.setDebuggerEnabled(true);

			try {
				if (ACCEPT_ALL_CERTIFICATES)
					TLSUtils.acceptAllCertificates(builder);
			} catch (KeyManagementException | NoSuchAlgorithmException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}

			builder.setHostnameVerifier(new HostnameVerifier() {

				@Override
				public boolean verify(String arg0, SSLSession arg1) {
					return true;
				}
			});
			
			//builder.set
			config = builder.build();
	

			connection = new XMPPTCPConnection(config);
			connection.connect();

			if (google || GOOGLE_TALK_HOST.equals(hostnameOverride))
				username = username + "@" + serviceName;

			connection.addSyncStanzaListener(packetListener, null);
			connection.addConnectionListener(connectionListener);

			// Login
			connection.login(username, (String) data, serverResource);

			waitForBindResult();

			JivePropertiesManager.setJavaObjectEnabled(true);

		} catch (final XMPPException | SmackException | IOException e) {
			throw new ContainerConnectException("Connect failed", e);
		}
		return jid;
	}

	private void waitForBindResult() throws IOException {
		// We'll wait a maximum of
		long bindTimeout = System.currentTimeMillis() + BIND_TIMEOUT;
		synchronized (bindLock) {
			while (jid == null && System.currentTimeMillis() < bindTimeout) {
				try {
					bindLock.wait(1000);
				} catch (InterruptedException e) {
				}
			}
			if (jid == null)
				throw new IOException("timeout waiting for server bind result");
			isConnected = true;
		}
	}

	private String getClientIdentifier() {
		return CLIENT_TYPE + handler.getEventHandlerID().getName();
	}

	/*
	 * public void sendPacket(Stanza packet) throws NotConnectedException { if
	 * (connection != null) connection.sendStanza(packet); }
	 */
	public synchronized void disconnect() {
		disconnecting = true;
		if (isStarted()) {
			stop();
		}
		if (connection != null) {
			connection.removeSyncStanzaListener(packetListener);
			connection.removeConnectionListener(connectionListener);
			connection.disconnect();
			connection = null;
			synchronized (bindLock) {
				jid = null;
				isConnected = false;
			}
		}
	}

	public synchronized boolean isConnected() {
		return (isConnected);
	}

	public synchronized ID getLocalID() {
		if (!isConnected())
			return null;
		try {
			return IDFactory.getDefault().createID(namespace.getName(), new Object[] { connection.getStreamId() });
		} catch (final Exception e) {
			logException("Exception in getLocalID", e);
			return null;
		}
	}

	public synchronized void start() {
		if (isStarted())
			return;
		isStarted = true;
	}

	public boolean isStarted() {
		return isStarted;
	}

	public synchronized void stop() {
		isStarted = false;
	}

	protected void handleConnectionClosed(Exception e) {
		if (!disconnecting) {
			disconnecting = true;
			handler.handleDisconnectEvent(new DisconnectEvent(this, e, null));
		}
	}

	protected void handlePacket(Stanza arg0) {
		handleJidPacket(arg0);
		try {
			final Object val = JivePropertiesManager.getProperty(arg0, OBJECT_PROPERTY_NAME);
			if (val != null) {
				handler.handleAsynchEvent(new ECFConnectionObjectPacketEvent(this, arg0, val));
			} else {
				handler.handleAsynchEvent(new ECFConnectionPacketEvent(this, arg0));
			}
		} catch (final IOException e) {
			logException("Exception in handleAsynchEvent", e);
			try {
				disconnect();
			} catch (final Exception e1) {
				logException("Exception in disconnect()", e1);
			}
		}
	}

	private void handleJidPacket(Stanza packet) {
		if (jid != null)
			return;
		if (packet instanceof IQ) {
			IQ iqPacket = (IQ) packet;
			if (iqPacket.getType().equals(IQ.Type.result) && iqPacket instanceof Bind) {
				Bind bindPacket = (Bind) iqPacket;
				synchronized (bindLock) {
					jid = bindPacket.getJid().toString();
					bindLock.notify();
				}
			}
		}
	}

	public synchronized void sendAsynch(ID receiver, byte[] data) throws IOException {
		if (data == null)
			throw new IOException("no data");
		final Message aMsg = new Message();
		JivePropertiesManager.addProperty(aMsg, OBJECT_PROPERTY_NAME, data);
		sendMessage(receiver, aMsg);
	}

	protected void sendMessage(ID receiver, Message aMsg) throws IOException {
		synchronized (this) {
			if (!isConnected())
				throw new IOException("not connected");
			try {
				if (receiver == null)
					throw new IOException("receiver cannot be null for xmpp instant messaging");
				else if (receiver instanceof XMPPID) {
					final XMPPID rcvr = (XMPPID) receiver;
					aMsg.setType(Message.Type.chat);
					final String receiverName = rcvr.getFQName();
					ChatManager chatManager = ChatManager.getInstanceFor(connection);
					final Chat localChat = chatManager.createChat(receiverName, new ChatMessageListener() {
						public void processMessage(Chat chat, Message message) {
						}
					});
					localChat.sendMessage(aMsg);
				} else if (receiver instanceof XMPPRoomID) {
					final XMPPRoomID roomID = (XMPPRoomID) receiver;
					aMsg.setType(Message.Type.groupchat);
					final String to = roomID.getMucString();
					aMsg.setTo(to);
					connection.sendStanza(aMsg);
				} else
					throw new IOException("receiver must be of type XMPPID or XMPPRoomID");
			} catch (final NotConnectedException e) {
				final IOException result = new IOException("XMPPException in sendMessage: " + e.getMessage());
				result.setStackTrace(e.getStackTrace());
				throw result;
			}
		}
	}

	public synchronized Object sendSynch(ID receiver, byte[] data) throws IOException {
		if (data == null)
			throw new IOException("data cannot be null");
		// This is assumed to be disconnect...so we'll just disconnect
		// disconnect();
		return null;
	}

	public void addListener(IConnectionListener listener) {
	}

	public void removeListener(IConnectionListener listener) {
	}

	public void sendMessage(ID target, String message) throws IOException {
		if (target == null)
			throw new IOException("target cannot be null");
		if (message == null)
			throw new IOException("message cannot be null");
		final Message aMsg = new Message();
		aMsg.setBody(message);
		sendMessage(target, aMsg);
	}

	@SuppressWarnings("rawtypes")
	public static Map getPropertiesFromPacket(Stanza packet) {
		return JivePropertiesManager.getProperties(packet);
	}

	public static Stanza setPropertiesInPacket(Stanza input, @SuppressWarnings("rawtypes") Map properties) {
		if (properties != null) {
			for (@SuppressWarnings("rawtypes")
			final Iterator i = properties.keySet().iterator(); i.hasNext();) {
				final Object keyo = i.next();
				final Object val = properties.get(keyo);
				final String key = (keyo instanceof String) ? (String) keyo : keyo.toString();
				JivePropertiesManager.addProperty(input, key, val);
			}
		}
		return input;
	}

	public void sendMessage(ID target, ID thread, Type type, String subject, String body,
			@SuppressWarnings("rawtypes") Map properties2) throws IOException {
		if (target == null)
			throw new IOException("XMPP target for message cannot be null");
		if (body == null)
			body = "";
		final Message aMsg = new Message();
		aMsg.setBody(body);
		if (thread != null)
			aMsg.setThread(thread.getName());
		if (type != null)
			aMsg.setType(type);
		if (subject != null)
			aMsg.setSubject(subject);
		setPropertiesInPacket(aMsg, properties2);
		sendMessage(target, aMsg);
	}

	public void sendPresenceUpdate(ID target, Presence presence) throws IOException {
		if (presence == null)
			throw new IOException("presence cannot be null");
		presence.setFrom(connection.getUser());
		if (target != null)
			presence.setTo(target.getName());
		synchronized (this) {
			if (!isConnected())
				throw new IOException("not connected");
			try {
				connection.sendStanza(presence);
			} catch (NotConnectedException e) {
				throw new IOException("Not connected", e);
			}
		}
	}

	public void sendRosterAdd(String user, String name, String[] groups) throws IOException, XMPPException {
		final Roster r = getRoster();
		try {
			r.createEntry(user, name, groups);
		} catch (NotConnectedException | NotLoggedInException | NoResponseException e) {
			throw new IOException("Cannot create entry", e);
		}
	}

	public void sendRosterRemove(String user) throws XMPPException, IOException {
		final Roster r = getRoster();
		try {
			final RosterEntry re = r.getEntry(user);
			r.removeEntry(re);
		} catch (NotLoggedInException | NoResponseException | NotConnectedException e) {
			throw new IOException("Cannot remove entry", e);
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.ecf.provider.xmpp.IIMMessageSender#getRoster()
	 */
	public Roster getRoster() throws IOException {
		if (connection == null || !connection.isConnected())
			return null;
		return Roster.getInstanceFor(connection);
	}

}
