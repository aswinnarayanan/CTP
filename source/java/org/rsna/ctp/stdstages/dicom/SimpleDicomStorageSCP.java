/*---------------------------------------------------------------
*  Copyright 2005 by the Radiological Society of North America
*
*  This source software is released under the terms of the
*  RSNA Public License (http://mirc.rsna.org/rsnapubliclicense)
*----------------------------------------------------------------*/

package org.rsna.ctp.stdstages.dicom;

import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedList;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.apache.log4j.Logger;
import org.dcm4che.data.Command;
import org.dcm4che.data.Dataset;
import org.dcm4che.data.DcmObjectFactory;
import org.dcm4che.data.DcmParser;
import org.dcm4che.data.DcmParserFactory;
import org.dcm4che.data.FileMetaInfo;
import org.dcm4che.dict.Status;
import org.dcm4che.dict.Tags;
import org.dcm4che.dict.UIDs;
import org.dcm4che.dict.VRs;
import org.dcm4che.net.AcceptorPolicy;
import org.dcm4che.net.ActiveAssociation;
import org.dcm4che.net.Association;
import org.dcm4che.net.AssociationFactory;
import org.dcm4che.net.DcmServiceBase;
import org.dcm4che.net.DcmServiceRegistry;
import org.dcm4che.net.Dimse;
import org.dcm4che.server.DcmHandler;
import org.dcm4che.server.Server;
import org.dcm4che.server.ServerFactory;
import org.dcm4che.util.DcmProtocol;

import org.rsna.ui.FileEvent;
import org.rsna.ui.FileEventListener;
import org.rsna.ctp.objects.DicomObject;
import org.rsna.util.FileUtil;

public class SimpleDicomStorageSCP extends DcmServiceBase {

    final static Logger logger = Logger.getLogger(SimpleDicomStorageSCP.class);

    private final static ServerFactory srvFact = ServerFactory.getInstance();
    private final static AssociationFactory fact = AssociationFactory.getInstance();
	final static DcmParserFactory pFact = DcmParserFactory.getInstance();
	final static DcmObjectFactory oFact = DcmObjectFactory.getInstance();

    private DcmProtocol protocol = DcmProtocol.DICOM;

    private Dataset overwrite = oFact.newDataset();
    private AcceptorPolicy policy = fact.newAcceptorPolicy();
    private DcmServiceRegistry services = fact.newDcmServiceRegistry();
    private DcmHandler handler = srvFact.newDcmHandler(policy, services);
    private Server server = srvFact.newServer(handler);

    private final int bufferSize = 2048;
	private final int maxPDULength = 16352;
	private final int soCloseDelay = 500;
	private final int dimseTimeout = 0;
	private final int rqTimeout = 20000; //changed from 10000
	private final int maxClients = 50; //changed from 10
    private final long rspDelay = 0L;

	private File directory = null;
	PCTable pcTable = null;

	HashSet<FileEventListener> listeners;

    public SimpleDicomStorageSCP(File directory, int port) {
		this.directory = directory;
		pcTable = PCTable.getInstance();

		directory.mkdirs();
		listeners = new HashSet<FileEventListener>();
        initServer(port);
        initPolicy();
    }

    public void start() throws IOException {
        server.start();
    }

    public void stop() {
		server.stop();
	}

    //Note: this method does not handle file sets.
    protected void doCStore(ActiveAssociation assoc, Dimse rq, Command rspCmd)
        		throws IOException {
        InputStream in = rq.getDataAsStream();
        try {
			Command rqCmd = rq.getCommand();
			Association a = assoc.getAssociation();
			String calledAET = a.getCalledAET();
			String callingAET = a.getCallingAET();
			String connectionIP = a.getSocket().getInetAddress().getHostAddress();
			String currentUID = rqCmd.getAffectedSOPInstanceUID();
			String name = rqCmd.getAffectedSOPInstanceUID() + ".dcm";

			FileMetaInfo fmi = objFact.newFileMetaInfo(
					rqCmd.getAffectedSOPClassUID(),
					rqCmd.getAffectedSOPInstanceUID(),
					rq.getTransferSyntaxUID());

			storeToDir(in, fmi, name);
        }
        catch (IOException ioe) { ioe.printStackTrace(); }
        finally { in.close(); }
        rspCmd.putUS(Tags.Status, Status.Success);
    }

    //Store the object in the directory.
    private void storeToDir(InputStream in,
    						FileMetaInfo fmi,
    						String name) throws IOException {
		File tempFile = new File(directory, name+".partial");
		File savedFile = new File(directory, name);
		OutputStream out = null;
        try {
			out = new BufferedOutputStream(new FileOutputStream(tempFile));
            fmi.write(out);
            copy(in, out, -1);
            out.close();
            out = null;
            tempFile.renameTo(savedFile);
        }
        catch (Exception ex) {
			logger.warn("Unable to store a received file.",ex);
			savedFile = null;
		}
        finally { FileUtil.close(out); }
        if (savedFile != null) sendFileEvent(savedFile);
    }

    private void copy(InputStream in, OutputStream out, int totLen) throws IOException {
        int toRead = (totLen == -1) ? Integer.MAX_VALUE : totLen;
		byte[] buffer = new byte[bufferSize];
		for (int len; toRead > 0; toRead -= len) {
			len = in.read(buffer, 0, Math.min(toRead, buffer.length));
			if (len == -1) {
				if (totLen == -1) return;
				throw new EOFException();
			}
			out.write(buffer, 0, len);
		}
    }

    private void initServer(int port) {
        server.setPort(port);
        server.setMaxClients(maxClients);
        handler.setRqTimeout(rqTimeout);
        handler.setDimseTimeout(dimseTimeout);
        handler.setSoCloseDelay(soCloseDelay);
        handler.setPackPDVs(false);
    }

    private void initPolicy() {
        policy.setCalledAETs(null);
        policy.setCallingAETs(null);
        policy.setMaxPDULength(maxPDULength);
        policy.setAsyncOpsWindow(0, 1);
        Enumeration<String> en = pcTable.keys();
        while (en.hasMoreElements()) {
			String asUID = en.nextElement();
            List<String> uidList = pcTable.get(asUID);
			String[] tsUIDs = new String[uidList.size()];
			tsUIDs = uidList.toArray(tsUIDs);
			policy.putPresContext(asUID, tsUIDs);
			services.bind(asUID, this);
        }
    }

	/**
	 * Add a FileEventListener.
	 * @param listener the FileEventListener.
	 */
	public synchronized void addFileEventListener(FileEventListener listener) {
		listeners.add(listener);
	}

	/**
	 * Remove a FileEventListener.
	 * @param listener the FileEventListener.
	 */
	public synchronized void removeFileEventListener(FileEventListener listener) {
		listeners.remove(listener);
	}

	//Send a FileEvent to all FileEventListeners.
	private synchronized void sendFileEvent(File file) {
		FileEvent event = new FileEvent(this, file);
		for (FileEventListener listener : listeners) {
			listener.fileEventOccurred(event);
		}
	}

}
