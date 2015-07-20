/****************************************************************************
 * Copyright (c) 2015 Composent, Inc. and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    Composent, Inc. - initial API and implementation
 *****************************************************************************/

package org.eclipse.ecf.internal.provider.xmpp;

import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.IAdapterManager;
import org.eclipse.ecf.core.ContainerCreateException;
import org.eclipse.ecf.core.identity.ID;
import org.eclipse.ecf.core.identity.IDCreateException;
import org.eclipse.ecf.core.identity.Namespace;
import org.eclipse.ecf.core.util.ECFException;
import org.eclipse.ecf.internal.provider.xmpp.smack.ECFConnection;
import org.eclipse.ecf.presence.chatroom.ChatRoomCreateException;
import org.eclipse.ecf.presence.chatroom.IChatRoomContainer;
import org.eclipse.ecf.presence.chatroom.IChatRoomInfo;
import org.eclipse.ecf.presence.chatroom.IChatRoomInvitationListener;
import org.eclipse.ecf.presence.chatroom.IChatRoomInvitationSender;
import org.eclipse.ecf.presence.chatroom.IChatRoomManager;
import org.eclipse.ecf.presence.history.IHistory;
import org.eclipse.ecf.presence.history.IHistoryManager;
import org.eclipse.ecf.provider.xmpp.identity.XMPPID;
import org.eclipse.ecf.provider.xmpp.identity.XMPPRoomID;
import org.eclipse.osgi.util.NLS;
import org.jivesoftware.smack.SmackException;
import org.jivesoftware.smack.SmackException.NoResponseException;
import org.jivesoftware.smack.SmackException.NotConnectedException;
import org.jivesoftware.smack.XMPPConnection;
import org.jivesoftware.smack.XMPPException;
import org.jivesoftware.smack.XMPPException.XMPPErrorException;
import org.jivesoftware.smack.packet.Message;
import org.jivesoftware.smack.packet.XMPPError;
import org.jivesoftware.smack.packet.XMPPError.Condition;
import org.jivesoftware.smackx.disco.ServiceDiscoveryManager;
import org.jivesoftware.smackx.disco.packet.DiscoverItems;
import org.jivesoftware.smackx.disco.packet.DiscoverItems.Item;
import org.jivesoftware.smackx.muc.HostedRoom;
import org.jivesoftware.smackx.muc.InvitationListener;
import org.jivesoftware.smackx.muc.MultiUserChat;
import org.jivesoftware.smackx.muc.MultiUserChatManager;
import org.jivesoftware.smackx.muc.RoomInfo;
import org.jivesoftware.smackx.xdata.Form;
import org.jivesoftware.smackx.xdata.packet.DataForm;

public class XMPPChatRoomManager implements IChatRoomManager {

	private static final String PROP_XMPP_SUBJECT = "subject"; //$NON-NLS-1$

	// key in the create room configuration in order to find the please to find
	// the conference rooms on the XMPP server
	public static final String PROP_XMPP_CONFERENCE = "conference"; //$NON-NLS-1$

	private ID containerID = null;

	private Namespace connectNamespace = null;

	private final List<IChatRoomInvitationListener> invitationListeners = new ArrayList<IChatRoomInvitationListener>();

	private ECFConnection ecfConnection = null;

	private final List<IChatRoomContainer> chatrooms = new ArrayList<IChatRoomContainer>();

	private ID connectedID = null;

	private final IChatRoomInvitationSender invitationSender = new IChatRoomInvitationSender() {

		public void sendInvitation(ID room, ID targetUser, String subject, String body) throws ECFException {
			XMPPChatRoomManager.this.sendInvitation(room, targetUser, subject, body);
		}

	};

	public XMPPChatRoomManager(ID containerID) {
		this.containerID = containerID;
	}

	/**
	 * @param room
	 * @param targetUser
	 * @param subject
	 * @param body
	 */
	protected void sendInvitation(ID room, ID targetUser, String subject, String body) throws ECFException {
		final XMPPChatRoomContainer chatRoomContainer = getChatRoomContainer(room);
		if (chatRoomContainer == null)
			throw new ECFException(NLS.bind(Messages.XMPPChatRoomManager_ROOM_NOT_FOUND, room.getName()));
		chatRoomContainer.sendInvitation(targetUser, subject, body);
	}

	protected void addChat(XMPPChatRoomContainer container) {
		synchronized (chatrooms) {
			chatrooms.add(container);
		}
	}

	protected void removeChat(XMPPChatRoomContainer container) {
		synchronized (chatrooms) {
			chatrooms.remove(container);
		}
	}

	protected XMPPChatRoomContainer getChatRoomContainer(ID roomID) {
		if (roomID == null)
			return null;
		List<IChatRoomContainer> toNotify = null;
		synchronized (chatrooms) {
			toNotify = new ArrayList<IChatRoomContainer>(chatrooms);
		}

		for (final Iterator<IChatRoomContainer> i = toNotify.iterator(); i.hasNext();) {
			final XMPPChatRoomContainer container = (XMPPChatRoomContainer) i.next();
			final ID containerRoomID = container.getConnectedID();
			if (containerRoomID == null)
				continue;
			if (containerRoomID.equals(roomID))
				return container;
		}
		return null;
	}

	protected ID createRoomIDFromName(String from) {
		try {
			return new XMPPRoomID(connectNamespace, ecfConnection.getXMPPConnection(), from);
		} catch (final URISyntaxException e) {
			return null;
		}
	}

	protected ID createUserIDFromName(String name) {
		ID result = null;
		try {
			result = new XMPPID(connectNamespace, name);
			return result;
		} catch (final Exception e) {
			return null;
		}
	}

	public void setConnection(Namespace connectNamespace, ID connectedID, ECFConnection connection) {
		this.connectNamespace = connectNamespace;
		this.connectedID = connectedID;
		this.ecfConnection = connection;
		if (connection != null) {
			// Setup invitation requestListener
			MultiUserChatManager chatManager = MultiUserChatManager.getInstanceFor(ecfConnection.getXMPPConnection());
			chatManager.addInvitationListener(new InvitationListener() {

				@Override
				public void invitationReceived(XMPPConnection conn, MultiUserChat room, String inviter, String reason,
						String password, Message message) {
					fireInvitationReceived(createRoomIDFromName(room.getRoom().toString()),
							createUserIDFromName(inviter), createUserIDFromName(message.getTo().toString()),
							message.getSubject(), reason);
				}
			});
		} else {
			disposeChatRooms();
		}
	}

	protected void disposeChatRooms() {
		List<IChatRoomContainer> toNotify = null;
		synchronized (chatrooms) {
			toNotify = new ArrayList<IChatRoomContainer>(chatrooms);
			chatrooms.clear();
		}
		for (final Iterator<IChatRoomContainer> i = toNotify.iterator(); i.hasNext();) {
			final IChatRoomContainer cc = (IChatRoomContainer) i.next();
			cc.dispose();
		}
	}

	public void dispose() {
		synchronized (invitationListeners) {
			invitationListeners.clear();
		}
		containerID = null;
		connectNamespace = null;
		disposeChatRooms();
		setConnection(null, null, null);
	}

	class ECFRoomInfo implements IChatRoomInfo {

		RoomInfo info;

		XMPPRoomID roomID;

		ID connectedID;

		public ECFRoomInfo(XMPPRoomID roomID, RoomInfo info, ID connectedID) {
			this.roomID = roomID;
			this.info = info;
			this.connectedID = connectedID;
		}

		public String getDescription() {
			return info.getDescription();
		}

		public String getSubject() {
			return info.getSubject();
		}

		public ID getRoomID() {
			return roomID;
		}

		public int getParticipantsCount() {
			return info.getOccupantsCount();
		}

		public String getName() {
			return roomID.getLongName();
		}

		public boolean isPersistent() {
			return info.isPersistent();
		}

		public boolean requiresPassword() {
			return info.isPasswordProtected();
		}

		public boolean isModerated() {
			return info.isModerated();
		}

		public ID getConnectedID() {
			return roomID;
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

		public IChatRoomContainer createChatRoomContainer() throws ContainerCreateException {
			XMPPChatRoomContainer chatContainer = null;
			if (ecfConnection == null)
				throw new ContainerCreateException(Messages.XMPPChatRoomManager_EXCEPTION_CONTAINER_DISCONNECTED);
			try {
				chatContainer = new XMPPChatRoomContainer(ecfConnection, connectNamespace);
				addChat(chatContainer);
				return chatContainer;
			} catch (final IDCreateException e) {
				throw new ContainerCreateException(Messages.XMPPChatRoomManager_EXCEPTION_CREATING_CHAT_CONTAINER, e);
			}
		}

		public String toString() {
			final StringBuffer buf = new StringBuffer("ECFRoomInfo["); //$NON-NLS-1$
			buf.append("id=").append(containerID).append(";name=" + getName()); //$NON-NLS-1$ //$NON-NLS-2$
			buf.append(";service=" + getConnectedID()); //$NON-NLS-1$
			buf.append(";count=" + getParticipantsCount()); //$NON-NLS-1$
			buf.append(";subject=" + getSubject()).append( //$NON-NLS-1$
					";desc=" + getDescription()); //$NON-NLS-1$
			buf.append(";pers=" + isPersistent()).append( //$NON-NLS-1$
					";pw=" + requiresPassword()); //$NON-NLS-1$
			buf.append(";mod=" + isModerated()).append("]"); //$NON-NLS-1$ //$NON-NLS-2$
			return buf.toString();
		}
	}

	public IChatRoomManager[] getChildren() {
		return new IChatRoomManager[0];
	}

	protected ID createIDFromHostedRoom(HostedRoom room) {
		try {
			return new XMPPRoomID(connectNamespace, ecfConnection.getXMPPConnection(), room.getJid().toString(),
					room.getName());
		} catch (final URISyntaxException e) {
			// debug output
			return null;
		}
	}

	public IChatRoomContainer findReceiverChatRoom(ID toID) {
		if (toID == null)
			return null;
		XMPPRoomID roomID = null;
		if (toID instanceof XMPPRoomID) {
			roomID = (XMPPRoomID) toID;
			final String mucname = roomID.getMucString();
			List<IChatRoomContainer> toNotify = null;
			synchronized (chatrooms) {
				toNotify = new ArrayList<IChatRoomContainer>(chatrooms);
			}
			for (final Iterator<IChatRoomContainer> i = toNotify.iterator(); i.hasNext();) {
				final IChatRoomContainer cont = (IChatRoomContainer) i.next();
				if (cont == null)
					continue;
				final ID tid = cont.getConnectedID();
				if (tid != null && tid instanceof XMPPRoomID) {
					final XMPPRoomID targetID = (XMPPRoomID) tid;
					final String tmuc = targetID.getMucString();
					if (tmuc.equals(mucname)) {
						return cont;
					}
				}
			}
		}
		return null;
	}

	private MultiUserChatManager getChatManager() {
		return MultiUserChatManager.getInstanceFor(ecfConnection.getXMPPConnection());
	}

	protected ID[] getChatRooms() {
		if (ecfConnection == null)
			return null;
		final XMPPConnection conn = ecfConnection.getXMPPConnection();
		if (conn == null)
			return null;
		final List<ID> result = new ArrayList<ID>();
		try {
			MultiUserChatManager manager = getChatManager();
			final Collection<String> svcs = manager.getServiceNames();
			for (final Iterator<String> svcsi = svcs.iterator(); svcsi.hasNext();) {
				final String svc = (String) svcsi.next();
				final List<HostedRoom> rooms = manager.getHostedRooms(svc);
				for (final Iterator<HostedRoom> roomsi = rooms.iterator(); roomsi.hasNext();) {
					final HostedRoom room = (HostedRoom) roomsi.next();
					final ID roomID = createIDFromHostedRoom(room);
					if (roomID != null)
						result.add(roomID);
				}
			}
		} catch (final XMPPException | NoResponseException | NotConnectedException e) {
			return null;
		}
		return (ID[]) result.toArray(new ID[] {});
	}

	protected IChatRoomInfo getChatRoomInfo(ID roomID) {
		if (!(roomID instanceof XMPPRoomID))
			return null;
		final XMPPRoomID cRoomID = (XMPPRoomID) roomID;
		try {
			final RoomInfo info = getChatManager().getRoomInfo(cRoomID.getMucString());
			if (info != null) {
				return new ECFRoomInfo(cRoomID, info, connectedID);
			}
		} catch (final XMPPException | NoResponseException | NotConnectedException e) {
			return null;
		}
		return null;
	}

	public IChatRoomInfo getChatRoomInfo(String roomname) {
		try {
			if (ecfConnection == null)
				return null;
			// Create roomid
			final XMPPConnection conn = ecfConnection.getXMPPConnection();
			final XMPPRoomID roomID = new XMPPRoomID(connectNamespace, conn, roomname);
			final String mucName = roomID.getMucString();
			final RoomInfo info = getChatManager().getRoomInfo(mucName);
			if (info != null) {
				return new ECFRoomInfo(roomID, info, connectedID);
			}
		} catch (final Exception e) {
			return null;
		}
		return null;
	}

	public IChatRoomInfo[] getChatRoomInfos() {
		final ID[] chatRooms = getChatRooms();
		if (chatRooms == null)
			return new IChatRoomInfo[0];
		final IChatRoomInfo[] res = new IChatRoomInfo[chatRooms.length];
		int count = 0;
		for (int i = 0; i < chatRooms.length; i++) {
			final IChatRoomInfo infoResult = getChatRoomInfo(chatRooms[i]);
			if (infoResult != null) {
				res[count++] = infoResult;
			}
		}
		final IChatRoomInfo[] results = new IChatRoomInfo[count];
		for (int i = 0; i < count; i++) {
			results[i] = res[i];
		}
		return results;
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

	public void addInvitationListener(IChatRoomInvitationListener listener) {
		synchronized (invitationListeners) {
			invitationListeners.add(listener);
		}
	}

	public void removeInvitationListener(IChatRoomInvitationListener listener) {
		synchronized (invitationListeners) {
			invitationListeners.remove(listener);
		}
	}

	protected void fireInvitationReceived(ID roomID, ID fromID, ID toID, String subject, String body) {
		List<IChatRoomInvitationListener> toNotify = null;
		synchronized (invitationListeners) {
			toNotify = new ArrayList<IChatRoomInvitationListener>(invitationListeners);
		}
		for (final Iterator<IChatRoomInvitationListener> i = toNotify.iterator(); i.hasNext();) {
			final IChatRoomInvitationListener l = (IChatRoomInvitationListener) i.next();
			l.handleInvitationReceived(roomID, fromID, subject, body);
		}
	}

	public IChatRoomManager getParent() {
		return null;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * org.eclipse.ecf.presence.chatroom.IChatRoomManager#createChatRoom(java.
	 * lang.String, java.util.Map)
	 */
	public IChatRoomInfo createChatRoom(String roomname, @SuppressWarnings("rawtypes") Map properties)
			throws ChatRoomCreateException {
		if (roomname == null)
			throw new ChatRoomCreateException(roomname, Messages.XMPPChatRoomManager_EXCEPTION_ROOM_CANNOT_BE_NULL);
		try {
			final String nickname = ecfConnection.getXMPPConnection().getUser().toString();
			final String server = ecfConnection.getXMPPConnection().getHost();
			final String domain = (properties == null) ? XMPPRoomID.DOMAIN_DEFAULT
					: (String) properties.get(PROP_XMPP_CONFERENCE);
			final String conference = XMPPRoomID.fixConferenceDomain(domain, server);
			final String roomID = roomname + XMPPRoomID.AT_SIGN + conference;
			// create proxy to the room
			final MultiUserChat muc = getChatManager().getMultiUserChat(roomID);

			if (!checkRoom(conference, roomID)) {
				// otherwise create a new one
				muc.create(nickname);
				muc.sendConfigurationForm(new Form(DataForm.Type.submit));
				final String subject = (properties == null) ? null : (String) properties.get(PROP_XMPP_SUBJECT);
				if (subject != null)
					muc.changeSubject(subject);
			}

			String longname = muc.getRoom().toString();
			if (longname == null || longname.length() <= 0) {
				longname = roomID;
			}

			final RoomInfo info = getChatManager().getRoomInfo(roomID);

			final XMPPRoomID xid = new XMPPRoomID(connectedID.getNamespace(), ecfConnection.getXMPPConnection(), roomID,
					longname);
			return new ECFRoomInfo(xid, info, connectedID);
		} catch (final URISyntaxException | XMPPException | SmackException e) {
			throw new ChatRoomCreateException("Could not create room=" + roomname, e.getMessage(), e);
		}
	}

	/**
	 * check if the MultiUserChat room is already existing on the XMPP server.
	 * 
	 * @param conference
	 * @param room
	 *            the name of the room
	 * @return true, if the room exists, false otherwise
	 * @throws XMPPException
	 */
	protected boolean checkRoom(String conference, String room) throws XMPPException {
		ServiceDiscoveryManager serviceDiscoveryManager = ServiceDiscoveryManager
				.getInstanceFor(ecfConnection.getXMPPConnection());
		DiscoverItems result = null;
		try {
			result = serviceDiscoveryManager.discoverItems(conference);
		} catch (NotConnectedException | NoResponseException e) {
			throw new XMPPErrorException("Could not discover service items",
					XMPPError.from(Condition.unexpected_request, "unexpected request"), e);
		}

		List<Item> items = result.getItems();
		for (Item item : items)
			if (room.equals(item.getEntityID()))
				return true;
		return false;
	}

	protected IHistoryManager chatRoomHistoryManager = new IHistoryManager() {

		public IHistory getHistory(ID chatRoomID, @SuppressWarnings("rawtypes") Map options) {
			// TODO Auto-generated method stub
			return null;
		}

		public boolean isActive() {
			// TODO Auto-generated method stub
			return false;
		}

		public void setActive(boolean active) {
			// TODO Auto-generated method stub

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

	};

	public IHistoryManager getHistoryManager() {
		return chatRoomHistoryManager;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * org.eclipse.ecf.presence.chatroom.IChatRoomManager#getInvitationSender()
	 */
	public IChatRoomInvitationSender getInvitationSender() {
		return invitationSender;
	}
}
