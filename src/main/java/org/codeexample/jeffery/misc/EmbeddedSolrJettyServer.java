package org.codeexample.jeffery.misc;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.RandomAccessFile;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.SocketException;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.util.Properties;

public class EmbeddedSolrJettyServer {
  
  private static final String ARG_PORT = "port";
  private static final String ARG_SEARCH_PORT_START_RANGE = "searchPortStartRange";
  private static final String ARG_SEARCH_PORT_END_RANGE = "searchPortEndRange";
  
  private static final String CONFILE_FILE = "config.properties";
  private static final String LOCK_FILE = "app.lock";
  private static final String RESULT_FILE = "result";
  
  private static final int DEFAULT_SEARCH_PORT_START_RANGE = 5000;
  private static final int DEFAULT_SEARCH_PORT_END_RANGE = 50000;
  
  private static File lockFile;
  private static FileChannel channel;
  private static FileLock lock;
  
  private static String SHUTDOWN_PASSWORD = "shutdown_passwd";
  private String appBaseLocation;
  
  /**
	 * @param args
	 *          format: start [suggesedtPort] [dynamicPort] | shutdown
	 */
  public static void main(String[] args) throws Exception {
    
    EmbeddedSolrJettyServer instance = new EmbeddedSolrJettyServer();
    instance.handleRequest(args);
  }
  
  private void handleRequest(String[] args) throws Exception {
    appBaseLocation = getBaseLocation();
    
    if (args.length < 1) {
      exitWithError("No arguments.");
      System.exit(-1);
    }
    
    String flag = args[0];
    if ("start".equalsIgnoreCase(flag)) {
      startEmbeddedJetty(args);
    } else if ("shutdown".equalsIgnoreCase(flag)) {
      Properties properties = readProperties();
      
      Object portObj = properties.get("port");
      if (portObj != null) {
        shutdown(Integer.valueOf(portObj.toString()), SHUTDOWN_PASSWORD);
      } else {
        System.err.println("Can't read port from properties file.");
      }
    }
  }

	/**
	 * Refer to:
	 * http://jimlife.wordpress.com/2008/07/21/java-application-make-sure
	 * -only-singleone
	 * -instance-running-with-file-lock-ampampampampamp-shutdownhook/
	 */
  private void startEmbeddedJetty(String[] args)
      throws UnsupportedEncodingException, FileNotFoundException, IOException,
      Exception, InterruptedException {
    Properties properties = readProperties();
    
    Integer port = null;
    boolean dynamicPort = false;
    
    // To simplify the code,
    // args maybe start, this case it will read port from config.properties, if
    // not set, will throw exception.
    // args maybe start portNumber
    // args maybe start portNumber dynamicPort
    if (args.length > 1) {
      // the second argument should be the port
      String portStr = args[1];
      try {
        port = Integer.parseInt(portStr);
      } catch (Exception e) {
        exitWithError("Parameter port " + portStr + " is not a valid number.");
      }
      
      if (args.length > 2) {
        // the third argument is a flag dynamicPort
        dynamicPort = "dynamicPort".equalsIgnoreCase(args[2]);
      }
    }
    if (port == null) {
      try {
        port = Integer.parseInt((String) properties.get(ARG_PORT));
      } catch (Exception ex) {
        // ignore this exception, as we will try to find other available port
      }
    }
    
    // check whether the application is already running
    lockFile = new File(appBaseLocation + LOCK_FILE);
    // Check if the lock exist
    if (lockFile.exists()) {
      // if exist try to delete it
      lockFile.delete();
    }
    // Try to get the lock
    channel = new RandomAccessFile(lockFile, "rw").getChannel();
    lock = channel.tryLock();
    if (lock == null) {
      // File is lock by other application
      channel.close();
      String portStr = (String) properties.get(ARG_PORT);
      if (portStr != null) {
        printSuccess(portStr, "Application is already running");
        // or call System.exit(0);
        return;
      }
    }
    // Add shutdown hook to release lock when application shutdown
    ShutdownHook shutdownHook = new ShutdownHook();
    Runtime.getRuntime().addShutdownHook(shutdownHook);
    
    // this should not throw exception
    int searchFrom = DEFAULT_SEARCH_PORT_START_RANGE;
    try {
      searchFrom = Integer.parseInt((String) properties
          .get(ARG_SEARCH_PORT_START_RANGE));
    } catch (Exception e) {
      searchFrom = DEFAULT_SEARCH_PORT_START_RANGE;
    }
    int searchTo = DEFAULT_SEARCH_PORT_END_RANGE;
    try {
      searchTo = Integer.parseInt((String) properties
          .get(ARG_SEARCH_PORT_END_RANGE));
    } catch (Exception e) {
      searchTo = DEFAULT_SEARCH_PORT_END_RANGE;
    }
    
    String sorlHome = appBaseLocation + "solr-home";
    if (!new File(sorlHome).exists()) {
      exitWithError("Solr home " + sorlHome
          + " doesn't exist or is not a folder.");
    }
    String solrWar = appBaseLocation + "solr.war";
    if (!new File(solrWar).exists()) {
      exitWithError("Solr war " + solrWar + "doesn't exist.");
    }
    Server server = null;
    if (dynamicPort) {
      // try 10 times
      for (int i = 0; i < 10; i++) {
        if (port == null) {
          port = findUnusedPort(searchFrom, searchTo);
        }
        if (port == null) {
          continue;
        }
        try {
          server = startEmbeddedJetty(sorlHome, solrWar, port);
          // break if start the server successfully.
          break;
        } catch (Exception e) {
          port = null;
          continue;
        }
      }
    } else {
      if (port == null) {
        exitWithError("In no-dynamicPort mode, a valid port is must specified in command line or config.proprties.");
      }
      server = startEmbeddedJetty(sorlHome, solrWar, port);
    }
    if (port == null) {
      exitWithError("Unable to find available port.");
    } else {
      properties.setProperty("port", port.toString());
      saveProperties(properties);
      printSuccess(port.toString(), "Server is started at port: " + port);
      if (server != null) {
        server.join();
      }
    }
  }
  
  /**
	 * From http://download.eclipse.org/jetty/stable-9/apidocs/org/eclipse/jetty/
	 * server/handler/ShutdownHandler.html
	 */
  private static void shutdown(int port, String shutdownPasswd) {
    try {
      URL url = new URL("http://localhost:" + port + "/shutdown?token="
          + shutdownPasswd + "&_exitJvm=true");
      HttpURLConnection connection = (HttpURLConnection) url.openConnection();
      connection.setRequestMethod("POST");
      connection.getResponseCode();
      System.out.println("Success=True");
      System.out.print("Message=Server (" + port + ") is shutdown");
    } catch (SocketException e) {
      System.out.println("Success=True");
      System.out.print("Message=Server is already not running.");
    } catch (Exception e) {
      System.out.println("Success=False");
      System.out.print("Message=" + e.getMessage());
    }
  }

  private Server startEmbeddedJetty(String sorlHome, String solrWar, int port)
      throws Exception {
    System.setProperty("solr.solr.home", sorlHome);
    System.setProperty("jetty.port", String.valueOf(port));
    System.setProperty("jetty.host", "0.0.0.0");
    
    Server server = null;
    try {
      server = new Server(port);
      
      // add solr.war
      WebAppContext webapp1 = new WebAppContext();
      webapp1.setContextPath("/solr");
      webapp1.setWar(solrWar);
      
      HandlerList handlers = new HandlerList();
      handlers.setHandlers(new Handler[] {webapp1,
          new ShutdownHandler(server, SHUTDOWN_PASSWORD)});
      server.setHandler(handlers);
      
      SelectChannelConnector connector = new SelectChannelConnector();
      connector.setReuseAddress(false);
      connector.setPort(port);
      server.setConnectors(new Connector[] {connector});
      
      server.start();
    } catch (Exception e) {
      if (server != null) {
        server.stop();
      }
      throw e;
    }
    return server;
  }
  
  private Integer findUnusedPort(int searchFrom, int searchTo) {
    ServerSocket s = null;
    for (int port = searchFrom; port < searchTo; port++) {
      try {
        s = new ServerSocket(port);
        // Not likely to happen, but if so: s.close throws exception, we will
        // continue and choose another port
        s.close();
        return port;
      } catch (Exception e) {
        continue;
      }
    }
    s.getLocalPort();
    return null;
  }
  
  private String getBaseLocation() throws UnsupportedEncodingException {
    File jarPath = new File(EmbeddedSolrJettyServer.class.getProtectionDomain()
        .getCodeSource().getLocation().getPath());
    
    String baseLocation = jarPath.getParent();
    // handle non-ascii character in path, such as Chinese
    baseLocation = URLDecoder.decode(baseLocation,
        System.getProperty("file.encoding"));
    if (!baseLocation.endsWith(File.separator)) {
      baseLocation = baseLocation + File.separator;
    }
    return baseLocation;
  }
  
  private void unlockFile() {
    // release and delete file lock
    try {
      if (lock != null) {
        lock.release();
        channel.close();
        lockFile.delete();
      }
    } catch (IOException e) {
      e.printStackTrace();
    }
  }
  
  /**
   * Write to a file
   * 
   * @param msg
   */
  private void exitWithError(String msg) throws IOException {
    BufferedWriter bw = null;
    try {
      bw = new BufferedWriter(new FileWriter(appBaseLocation + RESULT_FILE));
      bw.write("Success=False");
      bw.newLine();
      bw.write("Port=Unkown");
      bw.newLine();
      bw.write("Message=" + msg);
      bw.flush();
    } finally {
      if (bw != null) {
        bw.close();
      }
    }
    System.exit(-1);
  }
  
  private void printSuccess(String port, String msg) throws IOException {
    BufferedWriter bw = null;
    try {
      bw = new BufferedWriter(new FileWriter(appBaseLocation + RESULT_FILE));
      bw.write("Success=True");
      bw.newLine();
      bw.write("Port=" + port);
      bw.newLine();
      bw.write("Message=" + msg);
      bw.flush();
    } finally {
      if (bw != null) {
        bw.close();
      }
    }
  }
  
  private Properties readProperties() throws FileNotFoundException, IOException {
    String propertyFile = appBaseLocation + CONFILE_FILE;
    InputStream is = null;
    
    Properties properties = new Properties();
    try {
      is = new FileInputStream(propertyFile);
      properties.load(is);
      
    } finally {
      if (is != null) {
        is.close();
      }
    }
    return properties;
  }
  
  private class ShutdownHook extends Thread {
    public void run() {
      unlockFile();
    }
  }
  
  private void saveProperties(Properties properties)
      throws FileNotFoundException, IOException {
    String propertyFile = appBaseLocation + CONFILE_FILE;
    OutputStream os = null;
    try {
      os = new FileOutputStream(propertyFile);
      properties.store(os, "");
    } finally {
      if (os != null) {
        os.close();
      }
    }
  }
  
  static class NullPrintStream extends PrintStream {
    public NullPrintStream() {
      super(new OutputStream() {
        public void write(int b) {
          // DO NOTHING
        }
      });
      
    }
    
    @Override
    public void write(int b) {
      // do nothing
    }
    
  }
}