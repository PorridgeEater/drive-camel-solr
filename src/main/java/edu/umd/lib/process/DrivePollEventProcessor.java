package edu.umd.lib.process;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Stack;

import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.Processor;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.impl.DefaultExchange;
import org.apache.camel.impl.DefaultMessage;
import org.apache.log4j.Logger;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.impl.HttpSolrClient;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocumentList;
import org.json.JSONException;

import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.Change;
import com.google.api.services.drive.model.ChangeList;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.FileList;
import com.google.api.services.drive.model.StartPageToken;
import com.google.api.services.drive.model.TeamDrive;
import com.google.api.services.drive.model.TeamDriveList;

import edu.umd.lib.services.GoogleDriveConnector;

/****
 * Create a JSON to add the file to Solr. Connect to Drive and download the
 * whole file that needs to be indexed.
 *
 * @author audani
 *
 */
public class DrivePollEventProcessor implements Processor {

  private static Logger log = Logger.getLogger(DrivePollEventProcessor.class);
  Map<String, String> config;
  Drive service;
  SolrClient client;
  ProducerTemplate producer;
  final static String categories[] = { "policies", "reports", "guidelines", "links", "workplans", "minutes" };

  public DrivePollEventProcessor() {
  }

  public DrivePollEventProcessor(Map<String, String> config) {
    try {
      this.config = config;
      service = new GoogleDriveConnector(config).getDriveService();
      String solrPath = "{{solr.path.protocol}}://" + config.get("solrBaseUrl");
      log.debug(solrPath);
      client = new HttpSolrClient.Builder(solrPath).build();
    } catch (IOException e) {
      log.error(e.getMessage());
      e.printStackTrace();
    }
  }

  @Override
  public void process(Exchange exchange) throws Exception {

    producer = exchange.getContext().createProducerTemplate();
    if (service != null) {
      poll();
    } else {
      log.info(
          "Drive service has not been initialized. Please ensure that the Client Secret file exists and the Drive API has been enabled");
    }
  }

  /**
   * Connects to Drive and starts long polling Drive events. On an event, sends
   * exchange to ActionListener and updates the poll token.
   *
   * @param service
   */
  public void poll() {

    Path tokenProperties = Paths.get(this.config.get("tokenProperties"));
    try {
      if (Files.notExists(tokenProperties) || Files.size(tokenProperties) == 0) {
        //
        loadAllFiles();

      } else

      {
        // Fetch incremental changes i.e. changes that have occurred since the
        // last polling action
        String drivePageToken = null;

        do {
          TeamDriveList result = service.teamdrives().list()
              .setPageToken(drivePageToken)
              .setPageSize(100)
              .execute();

          List<TeamDrive> teamDrives = result.getTeamDrives();
          log.debug("Number of Team Drives:" + teamDrives.size());

          // Checking for the addition of a new Team Drive. If a new team drive
          // has been added with a published folder, we load the files
          // inside the published folder
          checkForNewTeamDrives(teamDrives, tokenProperties);

          for (TeamDrive teamDrive : teamDrives) {

            String pageToken = loadDriveChangesToken(teamDrive.getId());

            while (pageToken != null) {

              log.info("Checking changes for Drive " + teamDrive.getName());
              ChangeList changes = service.changes().list(pageToken)
                  .setFields("changes,nextPageToken,newStartPageToken")
                  .setIncludeTeamDriveItems(true)
                  .setSupportsTeamDrives(true)
                  .setTeamDriveId(teamDrive.getId())
                  .setPageSize(100)
                  .execute();

              for (Change change : changes.getChanges()) {

                File changeItem = change.getFile();

                if (changeItem != null) {
                  boolean isGoogleDoc = chkIfGoogleDoc(changeItem.getMimeType());

                  if (!isGoogleDoc) {
                    log.info("Change detected for item: " + changeItem.getId() + ":" + changeItem.getName());

                    String sourcePath = getSourcePath(changeItem);
                    log.debug("Source Path of accessed file:" + sourcePath);

                    // We are interested only in the changes that occur inside
                    // the published folder
                    if ("published".equals(sourcePath.split("/")[2])) {

                      // Delete event
                      if (change.getRemoved() || changeItem.getTrashed()) {

                        manageDeleteEvent(changeItem, sourcePath);
                      } else if (changeItem.getMimeType().equals("application/vnd.google-apps.folder")) {
                        if (!"published".equals(changeItem.getName())) {
                          // TODO Need to figure out how to handle folder
                          // rename/move event
                          // manageDirectoryEvents(changeItem, sourcePath);
                        }
                      } else {
                        log.debug("File Events");
                        manageFileEvents(changeItem, sourcePath);
                      }

                    } // End of published folder check
                  } // End of isGoogleDoc check
                } // End of null check
              } // End of for loop for changes

              // save latest page token
              if (changes.getNewStartPageToken() != null) {
                pageToken = changes.getNewStartPageToken();
                log.debug("Page token for team drive:" + teamDrive.getName() + ":" + pageToken);
                updateDriveChangesToken(teamDrive.getId(), pageToken);
              }

              pageToken = changes.getNextPageToken();

            }
          }
          drivePageToken = result.getNextPageToken();
        } while (drivePageToken != null);

      }
    } catch (Exception e) {
      log.error(e.getMessage());
      e.printStackTrace();
    }

  }

  /**
   * This method handles all events related to file changes
   *
   * @param changeItem
   * @param sourcePath
   * @throws IOException
   * @throws SolrServerException
   */
  public void manageFileEvents(File changeItem, String sourcePath) {

    // Either its a new file download, file rename or a file
    // update request

    String savedFilePath = null;
    String savedCheckSum = null;
    String savedFileName = null;
    QueryResponse response = null;
    log.debug("Id:" + changeItem.getId());
    SolrQuery query = new SolrQuery();
    log.debug("Id2" + changeItem.getId());
    query.setQuery("id:" + changeItem.getId());
    query.setFields("storagePath,fileChecksum,title");
    try {
      response = client.query(query);
      log.debug(response);
    } catch (IOException io) {
      log.debug(io.getMessage());
    } catch (SolrServerException so) {
      log.debug(so.getMessage());
    } catch (Exception e) {
      log.debug(e.getMessage());
    }
    SolrDocumentList results = response.getResults();
    log.debug(results);

    if (!results.isEmpty()) {
      savedFilePath = (String) results.get(0).getFieldValue("storagePath");
      savedCheckSum = (String) results.get(0).getFieldValue("fileChecksum");
      savedFileName = (String) results.get(0).getFieldValue("title");
      log.debug(savedFileName);
    }

    if (results == null || results.size() == 0) {
      // New file download request
      log.info("New File request");
      sendNewFileRequest(changeItem, sourcePath);
    } else {

      // Path file = Paths.get(storedFilePath);
      // Path storedFileName = file.getFileName();

      // Checking for file content update
      if (!changeItem.getMd5Checksum().equals(savedCheckSum)) {
        log.debug("File update request");
        sendUpdateContentRequest(changeItem);
      }

      // Checking for file rename
      if (!savedFileName.equals(changeItem.getName())) {
        log.debug("File Rename request");
        sendFileRenameRequest(sourcePath, changeItem);
      }

      // For checking file move, we are comparing the paths
      // without the file name.
      // (we are skipping the filename because in the
      // scenario that a file rename + file move event
      // occurs we are already handling file rename
      // separately, and if we keep the filename in the path
      // while checking for the file move event, this event
      // will be triggered even when the file rename occurs.
      String serverFilePath = sourcePath.substring(0, sourcePath.lastIndexOf("/"));
      String localFilePath = savedFilePath.substring(0, savedFilePath.lastIndexOf("/"));

      // Checking for file move request
      if (!serverFilePath.equals(localFilePath)) {
        log.info("File Move request");
        sendFileMoveRequest(changeItem, sourcePath);
      }
    }
  }

  /**
   * This method handles the delete event for files and directories
   *
   * @param changeItem
   * @param sourcePath
   */
  public void manageDeleteEvent(File changeItem, String sourcePath) {
    if ("application/vnd.google-apps.folder".equals(changeItem.getMimeType())) {

      log.info("Directory Delete request. Sending delete request for all files within the directory");

      List<File> files = fetchFileList(changeItem.getId());

      for (File file : files) {

        if (!"application/vnd.google-apps.folder".equals(file.getMimeType())) {
          sendDeleteRequest(file, getSourcePath(file));
        }
      }
    }

    if (!"application/vnd.google-apps.folder".equals(changeItem.getMimeType())) {

      log.info("File Delete request");
      sendDeleteRequest(changeItem, sourcePath);
    }
  }

  /**
   * This method is used to check if the file is a Google Doc
   *
   * @param mimeType
   * @return boolean
   */

  public boolean chkIfGoogleDoc(String mimeType) {

    if (mimeType.equals("application/vnd.google-apps.document")
        || mimeType.equals("application/vnd.google-apps.spreadsheet")
        || mimeType.equals("application/vnd.google-apps.drawing")
        || mimeType.equals("application/vnd.google-apps.presentation")
        || mimeType.equals("application/vnd.google-apps.script")) {
      return true;
    }

    return false;
  }

  /**
   * This method is used to check for new Team Drives that have been added after
   * the first run of this tool. It checks for new team drives, and creates them
   * on the local server along with all the published files and folders. It also
   * adds the token for the new drive in the properties file.
   *
   * @param service
   * @param teamDriveList
   * @param tokenProperties
   * @throws JSONException
   */

  public void checkForNewTeamDrives(List<TeamDrive> teamDriveList, Path tokenProperties) {
    try {
      if (Files.exists(tokenProperties) && !Files.isDirectory(tokenProperties)) {
        Properties props = new Properties();
        FileInputStream in = new FileInputStream(tokenProperties.toFile());
        props.load(in);
        in.close();

        if (teamDriveList.size() > props.size()) {
          for (TeamDrive teamDrive : teamDriveList) {
            if (!props.containsKey("drivetoken_" + teamDrive.getId())) {
              log.info("Team Drive ID:" + teamDrive.getId() + "\t Team Drive Name:" + teamDrive.getName());

              File publishedFolder = accessPublishedFolder(teamDrive);

              if (publishedFolder != null) {
                accessPublishedFiles(publishedFolder, teamDrive);

                StartPageToken response = service.changes().getStartPageToken()
                    .setSupportsTeamDrives(true)
                    .setTeamDriveId(teamDrive.getId())
                    .execute();

                updateDriveChangesToken(teamDrive.getId(), response.getStartPageToken());
              }

            }
          }
        }

        in.close();
      }
    } catch (FileNotFoundException e) {
      log.error("Properties file not found" + e.getMessage());
      e.printStackTrace();
    } catch (IOException e) {
      log.error("Properties file cannot be opened" + e.getMessage());
      e.printStackTrace();
    } catch (Exception e) {
      log.error(e.getMessage());
      e.printStackTrace();
    }

  }

  /**
   * Sends a new message exchange to ActionListener requesting to rename a file
   * on the local system.
   *
   * @param service
   * @param oldFile
   * @param newFile
   * @param changedFile
   * @param path
   * @throws IOException
   */
  public void sendFileRenameRequest(String srcPath, File changedFile) {

    String fileName = changedFile.getName();

    HashMap<String, String> headers = new HashMap<String, String>();
    headers.put("action", "rename_file");
    headers.put("source_id", changedFile.getId());
    headers.put("source_name", fileName);
    headers.put("storage_path", srcPath);
    headers.put("modified_time", changedFile.getModifiedTime().toString());
    sendActionExchange(headers, "");
  }

  /**
   * Sends a new message exchange to ActionListener requesting to delete a file
   * or folder
   *
   * @param service
   * @param change
   * @param sourcePath
   */
  public void sendDeleteRequest(File changeItem, String sourcePath) {

    HashMap<String, String> headers = new HashMap<String, String>();

    headers.put("action", "delete_file");
    headers.put("source_id", changeItem.getId());
    sendActionExchange(headers, "");
  }

  /**
   * Sends a new message exchange to ActionListener requesting to move a file
   *
   * @param file
   * @param localFilePath
   * @param localFilePathServerFile
   */

  public void sendFileMoveRequest(File file, String srcPath) {

    try {
      HashMap<String, String> headers = new HashMap<String, String>();
      Path acronymPropertiesFile = Paths.get(this.config.get("driveAcronymProperties"));
      Properties props = new Properties();

      headers.put("action", "move_file");
      headers.put("source_id", file.getId());
      headers.put("storage_path", srcPath);

      String paths[] = srcPath.split("/");
      String teamDrive = paths[1];
      headers.put("teamDrive", teamDrive);

      // We have this condition to check if the file has been moved to another
      // category. In that case, we need to update the category in Solr too.
      if (paths.length > 3) {
        for (String category : categories) {
          if (paths[3].equals(category)) {
            headers.put("category", category);
          }
        }
      }

      if (Files.exists(acronymPropertiesFile) && Files.size(acronymPropertiesFile) > 0) {

        FileInputStream in = new FileInputStream(acronymPropertiesFile.toFile());
        props.load(in);

        String acronym = (String) props.get(teamDrive.replaceAll(" ", "_"));
        if (acronym != null) {
          headers.put("group", acronym);
        } else {
          log.info("Could not set the group name. Ensure that it exists in the properties file.");
        }

        in.close();
      }

      sendActionExchange(headers, "");
    } catch (IOException e) {
      log.error(e.getMessage());
      e.printStackTrace();
    } catch (Exception e) {
      log.error(e.getMessage());
      e.printStackTrace();
    }

  }

  /**
   * Sends requests to make all directories and download all files associated
   * with the Team Drive.
   *
   * @param service
   * @throws IOException
   * @throws JSONException
   */
  public void loadAllFiles() {

    log.info("First time connecting to Google Drive Account");
    log.info("Sending requests to load all published files into Solr...");

    try {
      String pageToken = null;
      Path tokenProperties = Paths.get(this.config.get("tokenProperties"));
      Path driveAcronymProperties = Paths.get(this.config.get("driveAcronymProperties"));

      if (Files.notExists(tokenProperties)) {
        Files.createFile(tokenProperties);
      }

      if (Files.notExists(driveAcronymProperties)) {
        Files.createFile(driveAcronymProperties);
        log.info("Application paused for 60 seconds. Please populate the newly created drive acronym properties file.");
        Thread.sleep(60000);
      }

      do {

        TeamDriveList result = service.teamdrives().list()
            .setPageToken(pageToken)
            .execute();
        if (result != null) {
          List<TeamDrive> teamDrives = result.getTeamDrives();
          log.debug("Number of Team Drives:" + teamDrives.size());

          for (TeamDrive teamDrive : teamDrives) {
            log.debug("Team Drive ID:" + teamDrive.getId() + "\t Team Drive Name:" + teamDrive.getName());

            File publishedFolder = accessPublishedFolder(teamDrive);

            if (publishedFolder != null) {

              accessPublishedFiles(publishedFolder, teamDrive);
            }

            StartPageToken response = service.changes().getStartPageToken()
                .setSupportsTeamDrives(true)
                .setTeamDriveId(teamDrive.getId())
                .execute();

            updateDriveChangesToken(teamDrive.getId(), response.getStartPageToken());
          }

          pageToken = result.getNextPageToken();
        }
      } while (pageToken != null);
    } catch (IOException e) {
      log.error(e.getMessage());
      e.printStackTrace();
    } catch (InterruptedException e) {
      log.error(e.getMessage());
      e.printStackTrace();
    } catch (Exception e) {
      log.error(e.getMessage());
      e.printStackTrace();
    }
  }

  /**
   * Returns the published folder for a Team Drive
   *
   * @param service
   * @param teamDrive
   * @return the published folder for a team drive, if it exists
   */
  public File accessPublishedFolder(TeamDrive teamDrive) {
    try {
      FileList list = service.files().list()
          .setTeamDriveId(teamDrive.getId())
          .setSupportsTeamDrives(true)
          .setCorpora("teamDrive")
          .setFields("files(id,name,parents)")
          .setIncludeTeamDriveItems(true)
          .setQ("mimeType='application/vnd.google-apps.folder' and name='published' and trashed=false")
          .execute();

      List<File> fileList = list.getFiles();

      if (fileList.size() > 0) {
        return fileList.get(0);
      }

    } catch (IOException e) {
      log.error(e.getMessage());
      e.printStackTrace();
    } catch (Exception e) {
      log.error(e.getMessage());
      e.printStackTrace();
    }
    return null;
  }

  /**
   * This method lists all the files inside the published folder of a Team Drive
   * and sends a download request for these files
   *
   * @param service
   * @param file
   * @param tdteamDrive
   * @throws JSONException
   */
  public void accessPublishedFiles(File file, TeamDrive teamDrive) {

    try {

      String query = "'" + file.getId() + "' in parents and trashed=false";
      String pageToken = null;
      do {
        FileList list = service.files().list()
            .setQ(query)
            .setFields("nextPageToken,files(id,name,mimeType,parents,createdTime,modifiedTime,md5Checksum)")
            .setCorpora("teamDrive")
            .setIncludeTeamDriveItems(true)
            .setSupportsTeamDrives(true)
            .setTeamDriveId(teamDrive.getId())
            .setPageToken(pageToken)
            .execute();

        List<File> fileList = list.getFiles();

        for (File pubFile : fileList) {
          log.debug("File Name:" + pubFile.getName());
          log.debug("Mime type:" + pubFile.getMimeType());
          String path = getSourcePath(pubFile);
          if ("application/vnd.google-apps.folder".equals(pubFile.getMimeType())) {
            accessPublishedFiles(pubFile, teamDrive);
          } else {
            if (!chkIfGoogleDoc(pubFile.getMimeType()))
              sendNewFileRequest(pubFile, path);
          }

        }
        pageToken = list.getNextPageToken();
      } while (pageToken != null);

    } catch (IOException e) {
      log.error(e.getMessage());
      e.printStackTrace();
    } catch (Exception ex) {
      log.error(ex.getMessage());
      ex.printStackTrace();
    }
  }

  /**
   * Send a new message exchange to ActionListener requesting to download a file
   * to the local system.
   *
   * @param service
   * @param file
   * @param path
   * @throws IOException
   * @throws JSONException
   */
  public void sendNewFileRequest(File file, String path) {

    try {
      HashMap<String, String> headers = new HashMap<String, String>();

      Path acronymPropertiesFile = Paths.get(this.config.get("driveAcronymProperties"));
      Properties props = new Properties();

      headers.put("action", "new_file");
      headers.put("source_id", file.getId());
      headers.put("source_name", file.getName());
      headers.put("modified_time", file.getModifiedTime().toString());
      headers.put("file_checksum", file.getMd5Checksum());
      headers.put("storage_path", path);

      String paths[] = path.split("/");

      // parse teamDrive
      String teamDrive = paths[1];
      headers.put("teamDrive", teamDrive);

      // parse category
      if (paths.length > 3) {
        for (String category : categories) {
          if (paths[3].equals(category)) {
            headers.put("category", category);
          }
        }
      }

      // parse creation time from path
      String creation_time = Utilities.parseDate(path);
      // parse creation time from file
      if (creation_time == null) {
        creation_time = file.getCreatedTime().toString();
      }
      headers.put("creation_time", creation_time);

      if (Files.exists(acronymPropertiesFile) && Files.size(acronymPropertiesFile) > 0) {

        FileInputStream in = new FileInputStream(acronymPropertiesFile.toFile());
        props.load(in);

        String acronym = (String) props.get(teamDrive.replaceAll(" ", "_"));
        if (acronym != null) {
          headers.put("group", acronym);
        } else {
          log.info("Could not set the group name. Ensure that it exists in the properties file.");
        }

        in.close();
      }

      sendActionExchange(headers, "");

    } catch (IOException e) {
      log.error(e.getMessage());
      e.printStackTrace();
    } catch (Exception e) {
      log.error(e.getMessage());
      e.printStackTrace();
    }

  }

  /**
   * Send a new message exchange to ActionListener requesting to update an
   * existing file
   *
   * @param service
   * @param file
   */
  public void sendUpdateContentRequest(File file) {

    HashMap<String, String> headers = new HashMap<String, String>();
    headers.put("action", "update_file");
    headers.put("source_id", file.getId());
    headers.put("file_checksum", file.getMd5Checksum());
    headers.put("modified_time", file.getModifiedTime().toString());

    sendActionExchange(headers, "");
  }

  /**
   * Sends a new message exchange with given headers and body to ActionListener
   * route
   *
   * @param headers
   * @param body
   */
  public void sendActionExchange(HashMap<String, String> headers, String body) {
    Exchange exchange = new DefaultExchange(this.producer.getCamelContext());
    Message message = new DefaultMessage();
    message.setBody(body);

    for (Map.Entry<String, String> entry : headers.entrySet()) {
      message.setHeader(entry.getKey(), entry.getValue());
    }

    exchange.setIn(message);
    this.producer.send("direct:actions", exchange);
  }

  /****
   * Loads the poll token from the properties file, if the properties file
   * exists
   *
   * @param teamDriveId
   * @return the poll token for the Team Drive
   */
  public String loadDriveChangesToken(String teamDriveId) {
    String token = null;
    try {
      String drivePropFile = this.config.get("tokenProperties");
      Path f = Paths.get(drivePropFile);
      if (Files.exists(f) && !Files.isDirectory(f)) {
        Properties defaultProps = new Properties();
        FileInputStream in = new FileInputStream(drivePropFile);
        defaultProps.load(in);
        token = defaultProps.getProperty("drivetoken_" + teamDriveId);
        in.close();
      }
    } catch (FileNotFoundException e) {
      log.error("Properties file not found" + e.getMessage());
    } catch (IOException e) {
      log.error("Properties file cannot be opened" + e.getMessage());
    }
    return token;
  }

  /**
   * This method updates the token for the Team Drive in the properties file
   *
   * @param teamDriveId
   * @param driveToken
   */
  public void updateDriveChangesToken(String teamDriveId, String driveToken) {
    try {
      String propFilePath = this.config.get("tokenProperties");

      FileInputStream in = new FileInputStream(propFilePath);
      Properties props = new Properties();
      props.load(in);
      in.close();
      FileOutputStream out = new FileOutputStream(propFilePath);
      props.setProperty("drivetoken_" + teamDriveId, driveToken);
      props.store(out, "Drive Page token updated by the program - Do not delete");
      out.close();
    } catch (FileNotFoundException e) {
      log.error("Properties file not found" + e.getMessage());
    } catch (IOException e) {
      log.error("Properties file cannot be opened" + e.getMessage());
    } catch (Exception e) {
      log.error(e.getMessage());
      e.printStackTrace();
    }
  }

  /**
   * Gets the absolute path of a file or folder as it stands in Drive storage.
   *
   * @param service
   * @param item
   * @return the source path (as it stands in Google Drive) for a file
   * @throws IOException
   */
  public String getSourcePath(File item) {

    String itemName = item.getName();
    String parentID = item.getParents().get(0);
    Stack<String> path = new Stack<String>();
    StringBuilder fullPathBuilder = new StringBuilder();
    path.push(itemName);

    try {
      while (true) {

        File parent = service.files().get(parentID)
            .setSupportsTeamDrives(true)
            .setFields("id,name,parents")
            .execute();

        if (parent.getParents() == null) {
          String teamDriveName = service.teamdrives().get(parent.getId()).execute().getName();
          path.push(teamDriveName);
          break;
        } else {
          path.push(parent.getName());
          parentID = parent.getParents().get(0);
        }
      }
    } catch (Exception e) {
      log.error(e.getMessage());
      e.printStackTrace();
    }

    while (!path.isEmpty()) {
      fullPathBuilder.append("/").append(path.pop());
    }

    return fullPathBuilder.toString();
  }

  /**
   *
   * @param fileId
   * @return the list of files within a directory
   * @throws IOException
   */

  public List<File> fetchFileList(String fileId) {

    List<File> fullFilesList = new ArrayList<File>();
    String teamDriveId = null;

    try {
      teamDriveId = service.files().get(fileId)
          .setSupportsTeamDrives(true)
          .execute().getTeamDriveId();

      log.debug("Team DriveId:" + teamDriveId);
      String query = "'" + fileId + "' in parents and trashed=false";
      String pageToken = null;
      do {
        FileList list = service.files().list()
            .setQ(query)
            .setFields("nextPageToken,files(id,name,mimeType,parents)")
            .setCorpora("teamDrive")
            .setIncludeTeamDriveItems(true)
            .setSupportsTeamDrives(true)
            .setTeamDriveId(teamDriveId)
            .setPageToken(pageToken)
            .setPageSize(50)
            .execute();

        List<File> fileList = list.getFiles();

        for (File f : fileList) {
          if ("application/vnd.google-apps.folder".equals(f.getMimeType())) {
            fullFilesList.addAll(fetchFileList(f.getId()));
          } else {
            fullFilesList.add(f);
          }
        }

        pageToken = list.getNextPageToken();
      } while (pageToken != null);

    }

    catch (IOException e) {
      log.error(e.getMessage());
      e.printStackTrace();
    } catch (Exception e) {
      log.error(e.getMessage());
      e.printStackTrace();
    }

    return fullFilesList;
  }

}
