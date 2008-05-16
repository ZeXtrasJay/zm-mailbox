package com.zimbra.cs.datasource;
                             
import com.zimbra.cs.mailclient.imap.ImapConfig;
import com.zimbra.cs.mailclient.imap.ImapConnection;
import com.zimbra.cs.mailclient.imap.ListData;
import com.zimbra.cs.account.DataSource;
import com.zimbra.cs.mailbox.Folder;
import com.zimbra.common.service.ServiceException;
import com.zimbra.common.localconfig.LC;
import com.zimbra.common.util.DummySSLSocketFactory;
import com.zimbra.common.util.CustomSSLSocketFactory;
import com.zimbra.common.util.ZimbraLog;
import com.zimbra.common.util.Log;
import com.zimbra.common.util.StringUtil;
import com.zimbra.common.util.SystemUtil;

import java.io.IOException;
import java.util.List;
import java.util.Collections;
import java.util.Map;
import java.util.HashMap;

public class ImapSync extends AbstractMailItemImport {
    private final ImapConnection connection;
    private final Folder localRootFolder;
    private char delimiter; // IMAP hierarchy delimiter (0 if flat)
    private ImapFolderCollection trackedFolders;
    private Map<Integer, ImapFolderSync> syncedFolders;
    private boolean fullSync;

    private static final Log LOG = ZimbraLog.datasource;

    private static final boolean DEBUG = true;
    private static final boolean FAIL_ON_SYNC_ERROR = true;

    public ImapSync(DataSource ds) throws ServiceException {
        super(ds);
        connection = new ImapConnection(getImapConfig(ds));
        localRootFolder = ds.getMailbox().getFolderById(null, ds.getFolderId());
    }

    public ImapConnection getConnection() { return connection; }
    
    private static ImapConfig getImapConfig(DataSource ds) {
        ImapConfig config = new ImapConfig();
        config.setHost(ds.getHost());
        config.setPort(ds.getPort());
        config.setAuthenticationId(ds.getUsername());
        config.setMaxLiteralMemSize(LC.data_source_max_message_memory_size.intValue());
        config.setTlsEnabled(LC.javamail_imap_enable_starttls.booleanValue());
        config.setSslEnabled(ds.isSslEnabled());
        config.setDebug(DEBUG);
        config.setTrace(DEBUG);
        // config.setRawMode(true);
        if (LC.data_source_trust_self_signed_certs.booleanValue()) {
            config.setSSLSocketFactory(new DummySSLSocketFactory());
        } else {
            config.setSSLSocketFactory(new CustomSSLSocketFactory());
        }
        return config;
    }

    public synchronized String test() throws ServiceException {
        validateDataSource();
        try {
            connect();
        } catch (ServiceException e) {
            Throwable except = SystemUtil.getInnermostException(e);
            if (except == null) except = e;
            ZimbraLog.datasource.info("Error connecting to mail store: ", except);
            return except.toString();
        } finally {
            connection.close();
        }
        return null;
    }
    
    public synchronized void importData(boolean fullSync)
        throws ServiceException {
        validateDataSource();
        connect();
        this.fullSync = fullSync;
        try {
            syncFolders();
        } catch (IOException e) {
            throw ServiceException.FAILURE("Folder sync failed", e);
        } finally {
            connection.close();
        }
    }

    private void connect() throws ServiceException {
        if (!connection.isClosed()) return;
        try {
            connection.connect();
            connection.login(dataSource.getDecryptedPassword());
            delimiter = ImapUtil.getDelimiter(connection);
        } catch (IOException e) {
            connection.close();
            throw ServiceException.FAILURE(
                "Unable to connect to IMAP server: " + dataSource, e);
        }
    }

    private void syncFolders() throws ServiceException, IOException {
        trackedFolders = dataSource.getImapFolders();
        syncedFolders = new HashMap<Integer, ImapFolderSync>();
        // Synchronize local and remote folders
        syncRemoteFolders();
        syncLocalFolders();
        // Append new IMAP messages for folders which have been synchronized.
        // This is done after IMAP messages have been deleted in order to
        // avoid problems when local messages are moved between folders
        // (see bug 27924).
        for (ImapFolderSync ifs : syncedFolders.values()) {
            ifs.appendNewMessages();
        }
        // Any remaining folder trackers are for folders which were deleted
        // both locally and remotely.
        for (ImapFolder tracker : trackedFolders) {
            dataSource.deleteImapFolder(tracker);
        }
    }

    // Synchronize remote IMAP folders -> local folders
    private void syncRemoteFolders() throws ServiceException, IOException {
        for (ListData ld : ImapUtil.listFolders(connection)) {
            String path = ld.getMailbox();
            ImapFolder tracker = trackedFolders.getByRemotePath(path);
            if (tracker != null) {
                trackedFolders.remove(tracker);
            }
            try {
                ImapFolderSync ifs = new ImapFolderSync(this, tracker);
                tracker = ifs.syncFolder(ld, fullSync);
                if (tracker != null) {
                    syncedFolders.put(tracker.getItemId(), ifs);
                }
            } catch (Exception e) {
                LOG.error("Skipping synchronization of IMAP folder '%s' " +
                          "due to error", path, e);
                if (FAIL_ON_SYNC_ERROR) {
                    throw ServiceException.FAILURE(
                        "Synchronization of IMAP foldr '%s' failed", e);
                }
            }
        }
    }

    // Synchronize local folders -> IMAP folders
    private void syncLocalFolders() throws ServiceException, IOException {
        List<Folder> folders = localRootFolder.getSubfolderHierarchy();
        // Folder list is in depth-first order, so reverse entries so that
        // children are before parents. This avoids problems when deleting
        // folders.
        Collections.reverse(folders);
        for (Folder folder : folders) {
            int id = folder.getId();
            if (id != localRootFolder.getId() && !syncedFolders.containsKey(id)) {
                ImapFolder tracker = trackedFolders.getByItemId(id);
                if (tracker != null) {
                    trackedFolders.remove(tracker);
                }
                try {
                    ImapFolderSync ifs = new ImapFolderSync(this, tracker);
                    ifs.syncFolder(folder, fullSync);
                    syncedFolders.put(id, ifs);
                } catch (Exception e) {
                    LOG.error("Skipping synchronization of local folder " +
                              "'%s' (id = %d) due to error", folder.getName(),
                              folder.getId(), e);
                    if (FAIL_ON_SYNC_ERROR) {
                        throw ServiceException.FAILURE(
                            "Synchronization of local folder '%s' failed", e);
                    }
                }
            }
        }
    }

    /*
     * Returns the path to the Zimbra folder that stores messages for the given
     * IMAP folder. The Zimbra folder has the same path as the IMAP folder,
     * but is relative to the root folder specified by the DataSource.
     */
    String getLocalPath(String remotePath, char delimiter) {
        String relativePath = remotePath;
        if (delimiter != '/' && (remotePath.indexOf(delimiter) >= 0 ||
                                 remotePath.indexOf('/') >= 0)) {
            // Change remote path to use our separator
            String[] parts = remotePath.split("\\" + delimiter);
            for (int i = 0; i < parts.length; i++) {
                // TODO Handle case where separator is not valid in Zimbra folder name
                parts[i] = parts[i].replace('/', delimiter);
            }
            relativePath = StringUtil.join("/", parts);
        }
        String zimbraPath = dataSource.matchKnownLocalPath(relativePath);
        if ("".equals(zimbraPath)) {
            return null; // Do not synchronize folder
        }
        if (zimbraPath == null) {
            // Remove leading slashes and append to root folder
            while (relativePath.startsWith("/")) {
                relativePath = relativePath.substring(1);
            }
            if (localRootFolder.getId() == com.zimbra.cs.mailbox.Mailbox.ID_FOLDER_USER_ROOT) {
                zimbraPath = "/" + relativePath;
            } else {
                zimbraPath = localRootFolder.getPath() + "/" + relativePath;
            }
        }
        return zimbraPath;
    }

    /*
     * Returns the IMAP path name for the specified local folder. Returns null
     * if the folder should not be imported.
     */
    String getRemotePath(Folder folder) throws ServiceException {
        if (!localRootFolder.isDescendant(folder)) {
            return null;
        }
        String imapPath = dataSource.matchKnownRemotePath(folder.getPath());
        if ("".equals(imapPath)) {
            return null; // Ignore folder
        }
        if (imapPath == null) {
            if (folder.getId() < com.zimbra.cs.mailbox.Mailbox.FIRST_USER_ID) {
                return null;
            }
            // Determine imap path from folder path
            imapPath = folder.getPath();
            // Strip root path from folder path
            String rootPath = localRootFolder.getPath();
            if (!rootPath.endsWith("/")) {
                rootPath += "/";
            }
            if (!imapPath.startsWith(rootPath)) {
                return null; // Folder no longer data source root
            }
            imapPath = imapPath.substring(rootPath.length());
        }
        // Handling for IMAP folder delimiter different from Zimbra's
        if (delimiter != '/') {
            String[] parts = imapPath.split("/");
            for (int i = 0; i < parts.length; i++) {
                parts[i] = parts[i].replace(delimiter, '/');
            }
            imapPath = StringUtil.join(String.valueOf(delimiter), parts);
        }
        return imapPath;
    }
}
