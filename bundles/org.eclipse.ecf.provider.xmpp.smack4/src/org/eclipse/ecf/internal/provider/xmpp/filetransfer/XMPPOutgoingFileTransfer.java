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
package org.eclipse.ecf.internal.provider.xmpp.filetransfer;

import java.io.File;

import org.eclipse.core.runtime.IAdapterManager;
import org.eclipse.core.runtime.ISafeRunnable;
import org.eclipse.core.runtime.SafeRunner;
import org.eclipse.ecf.core.identity.ID;
import org.eclipse.ecf.core.identity.IDCreateException;
import org.eclipse.ecf.core.identity.IDFactory;
import org.eclipse.ecf.filetransfer.FileTransferJob;
import org.eclipse.ecf.filetransfer.IFileTransferInfo;
import org.eclipse.ecf.filetransfer.IFileTransferListener;
import org.eclipse.ecf.filetransfer.IOutgoingFileTransfer;
import org.eclipse.ecf.filetransfer.events.IFileTransferEvent;
import org.eclipse.ecf.filetransfer.events.IOutgoingFileTransferEvent;
import org.eclipse.ecf.filetransfer.events.IOutgoingFileTransferResponseEvent;
import org.eclipse.ecf.filetransfer.events.IOutgoingFileTransferSendDataEvent;
import org.eclipse.ecf.filetransfer.events.IOutgoingFileTransferSendDoneEvent;
import org.eclipse.ecf.internal.provider.xmpp.XmppPlugin;
import org.eclipse.ecf.provider.xmpp.identity.XMPPID;
import org.eclipse.osgi.util.NLS;
import org.jivesoftware.smack.SmackException;
import org.jivesoftware.smack.XMPPException;
import org.jivesoftware.smack.packet.XMPPError;
import org.jivesoftware.smack.packet.XMPPError.Condition;
import org.jivesoftware.smackx.filetransfer.FileTransfer.Status;
import org.jivesoftware.smackx.filetransfer.FileTransferManager;
import org.jivesoftware.smackx.filetransfer.OutgoingFileTransfer;
import org.jxmpp.stringprep.XmppStringprepException;

public class XMPPOutgoingFileTransfer implements IOutgoingFileTransfer {

	private final ID sessionID;
	private final XMPPID remoteTarget;
	private final IFileTransferListener listener;

	private File localFile;

	private long fileSize;

	private final OutgoingFileTransfer outgoingFileTransfer;

	private Status status;

	private Exception exception;

	private int originalOutputRequestTimeout = -1;

	private long outgoingFileTransferThreadSleepTime = 500;

	public XMPPOutgoingFileTransfer(FileTransferManager manager, XMPPID remoteTarget,
			IFileTransferInfo fileTransferInfo, IFileTransferListener listener, int outgoingRequestTimeout)
					throws XmppStringprepException {
		this.remoteTarget = remoteTarget;
		this.listener = listener;
		this.sessionID = createSessionID();
		final String fullyQualifiedName = remoteTarget.getFQName();
		// Set request timeout if we have a new value
		if (outgoingRequestTimeout != -1) {
			originalOutputRequestTimeout = OutgoingFileTransfer.getResponseTimeout();
			OutgoingFileTransfer.setResponseTimeout(outgoingRequestTimeout);
		}
		outgoingFileTransfer = manager.createOutgoingFileTransfer(fullyQualifiedName);
	}

	private ID createSessionID() {
		try {
			return IDFactory.getDefault().createGUID();
		} catch (final IDCreateException e) {
			throw new NullPointerException("cannot create id for XMPPOutgoingFileTransfer"); //$NON-NLS-1$
		}
	}

	public synchronized ID getRemoteTargetID() {
		return remoteTarget;
	}

	public ID getID() {
		return sessionID;
	}

	private void fireTransferListenerEvent(final IFileTransferEvent event) {
		SafeRunner.run(new ISafeRunnable() {
			@Override
			public void handleException(Throwable exception) {
				exception.printStackTrace(System.err);
			}

			@Override
			public void run() throws Exception {
				listener.handleTransferEvent(event);
			}
		});
	}

	class OutgoingEvent implements IOutgoingFileTransferEvent {
		public IOutgoingFileTransfer getSource() {
			return XMPPOutgoingFileTransfer.this;
		}
	}

	class OutgoingStatusEvent extends OutgoingEvent {
		public void setFileTransferJob(FileTransferJob job) {
		}
	}

	class OutgoingStatusProgressEvent extends OutgoingEvent {

		String toString(String type) {
			final StringBuffer buf = new StringBuffer(type + "["); //$NON-NLS-1$
			buf.append("isDone=" + getSource().isDone()); //$NON-NLS-1$
			buf.append(";bytesSent=").append(getSource().getBytesSent()); //$NON-NLS-1$
			buf.append(";exception=").append(getException()).append("]"); //$NON-NLS-1$ //$NON-NLS-2$
			return buf.toString();
		}
	}

	class OutgoingStatusDoneEvent extends OutgoingStatusProgressEvent implements IOutgoingFileTransferSendDoneEvent {
		public String toString() {
			return toString("OutgoingFileTransferSendDoneEvent");
		}
	}

	class OutgoingStatusDataEvent extends OutgoingStatusProgressEvent implements IOutgoingFileTransferSendDataEvent {
		public String toString() {
			return toString("OutgoingFileTransferSendDataEvent");
		}
	}

	class OutgoingRequestResponseEvent extends OutgoingStatusEvent implements IOutgoingFileTransferResponseEvent {
		private final boolean accepted;

		public OutgoingRequestResponseEvent(boolean accepted) {
			this.accepted = accepted;
		}

		@Override
		public boolean requestAccepted() {
			return accepted;
		}

		public String toString() {
			final StringBuffer buf = new StringBuffer("OutgoingFileTransferResponseEvent["); //$NON-NLS-1$
			buf.append("requestAccepted=").append(requestAccepted()).append("]"); //$NON-NLS-1$ //$NON-NLS-2$
			return buf.toString();
		}
	}

	private synchronized void setStatus(Status s) {
		this.status = s;
		if (Status.error.equals(this.status))
			this.exception = outgoingFileTransfer.getException();
	}

	private synchronized void setErrorStatus(Exception exception) {
		this.status = Status.error;
		this.exception = exception;
	}

	public synchronized void startSend(File localFile, String description) throws XMPPException {
		this.localFile = localFile;
		this.fileSize = localFile.length();

		final Thread transferThread = new Thread(new Runnable() {
			@Override
			public void run() {
				boolean done = false;
				boolean started = false;
				try {
					while (!done) {
						Status status = outgoingFileTransfer.getStatus();
						setStatus(status);
						if (status.equals(Status.refused)) {
							fireTransferListenerEvent(new OutgoingRequestResponseEvent(false));
							done = true;
						} else if (status.equals(Status.in_progress)) {
							if (!started) {
								fireTransferListenerEvent(new OutgoingRequestResponseEvent(true));
								started = true;
							}
							fireTransferListenerEvent(new OutgoingStatusDataEvent());
						} else if (status.equals(Status.error) || status.equals(Status.cancelled)
								|| status.equals(Status.complete)) {
							if (!started) {
								fireTransferListenerEvent(new OutgoingRequestResponseEvent(true));
								started = true;
							}
							done = true;
						}
						// sleep for a bit
						Thread.sleep(outgoingFileTransferThreadSleepTime);
					}
				} catch (Exception e) {
					setErrorStatus(e);
				} finally {
					// Reset request timeout
					if (originalOutputRequestTimeout != -1)
						OutgoingFileTransfer.setResponseTimeout(originalOutputRequestTimeout);
					// Then notify that the sending is done
					fireTransferListenerEvent(new OutgoingStatusDoneEvent());
				}
			}
		}, NLS.bind("XMPP outgoing filetransfer {0}", remoteTarget.toExternalForm()));

		transferThread.start();

		try {
			outgoingFileTransfer.sendFile(localFile, description);
		} catch (SmackException e1) {
			throw new XMPPException.XMPPErrorException("Could not start sending file",
					XMPPError.from(Condition.service_unavailable, "xmpp service unavailable error"), e1);
		}

	}

	public synchronized void cancel() {
		if (!outgoingFileTransfer.isDone())
			outgoingFileTransfer.cancel();
	}

	public synchronized File getLocalFile() {
		return localFile;
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

	public synchronized long getBytesSent() {
		return outgoingFileTransfer.getBytesSent();
	}

	public Exception getException() {
		return this.exception;
	}

	public synchronized double getPercentComplete() {
		return (fileSize <= 0) ? 1.0 : (((double) outgoingFileTransfer.getAmountWritten()) / ((double) fileSize));
	}

	public synchronized boolean isDone() {
		return outgoingFileTransfer.isDone();
	}

	public ID getSessionID() {
		return sessionID;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.ecf.filetransfer.IFileTransfer#getFileLength()
	 */
	public long getFileLength() {
		return fileSize;
	}

}
