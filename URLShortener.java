import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.sql.*;
import java.util.Date;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class URLShortener {

    static final File WEB_ROOT = new File(".");
    static final String DEFAULT_FILE = "index.html";
    static final String FILE_NOT_FOUND = "404.html";
    static final String METHOD_NOT_SUPPORTED = "not_supported.html";
    static final String REDIRECT_RECORDED = "redirect_recorded.html";
    static final String REDIRECT = "redirect.html";
    static final String NOT_FOUND = "notfound.html";
    static String DATABASE;
    // port to listen connection
    static final int PORT = 8080;

    // verbose mode
    static final boolean verbose = false;

    static final int NUM_THREADS = 8;
    static final ThreadPoolExecutor threadPool = (ThreadPoolExecutor) Executors.newFixedThreadPool(NUM_THREADS);
    static final int CACHE_CAPACITY = 100;

    public static void main(String[] args) {

        LRUCacheSync<String, String> LRUCache = new LRUCacheSync<>(CACHE_CAPACITY);

        DATABASE = "jdbc:sqlite:/virtual/" + args[0] + ".sqlite";

        try {
            ServerSocket serverConnect = new ServerSocket(PORT);
            System.out.println("Server started.\nListening for connections on port : " + PORT + " ...");

            Connection dbConnect = DriverManager.getConnection(DATABASE);
            Thread dataMigrateThread = new Thread(() -> {
                try {
                    migrateData(dbConnect);
                } catch (SQLException e) {
                    e.printStackTrace();
                }
            });
            dataMigrateThread.start();

            // we listen until user halts server execution
            while (true) {
                if (verbose) {
                    System.out.println("Connection opened. (" + new Date() + ")");
                }
                Socket clientConnect = serverConnect.accept();
                threadPool.execute(new Handler(clientConnect, dbConnect, LRUCache));
            }
        } catch (IOException e) {
            System.err.println("Server Connection error : " + e.getMessage());
        } catch (SQLException e) {
            System.err.println("SQLite3 Connection error : " + e.getMessage());
        }
    }

    private static String find(String shortURL, Connection dbConnect) throws SQLException {

        PreparedStatement statement = dbConnect.prepareStatement("SELECT longURL FROM URL WHERE shortURL = ?");
        statement.setString(1, shortURL);
        return statement.executeQuery().getString("longURL");

    }

    private static void save(String shortURL, String longURL, Connection dbConnect) throws SQLException {

        PreparedStatement statement = dbConnect
                .prepareStatement("INSERT OR REPLACE INTO URL (shortURL, longURL) VALUES (?, ?)");
        statement.setString(1, shortURL);
        statement.setString(2, longURL);
        statement.executeUpdate();
    }

    private static byte[] readFileData(File file, int fileLength) throws IOException {
        FileInputStream fileIn = null;
        byte[] fileData = new byte[fileLength];

        try {
            fileIn = new FileInputStream(file);
            fileIn.read(fileData);
        } finally {
            if (fileIn != null)
                fileIn.close();
        }

        return fileData;
    }

    static void migrateData(Connection dbConnect) throws SQLException {
        try {
            ServerSocket serverConnect = new ServerSocket(65535);
            while (true) {
                // accept connection from client
                // (socket that connects to the server)
                Socket clientConnect = serverConnect.accept();
                BufferedReader in = new BufferedReader(new InputStreamReader(clientConnect.getInputStream()));
                String line = in.readLine();
                String[] tokens = line.split(" ");
                if (Objects.equals(tokens[0], "GET")) {
                    PreparedStatement statement = dbConnect
                            .prepareStatement("SELECT longURL, shortURL FROM URL WHERE shortURL LIKE ?");
                    statement.setString(1, tokens[1] + "%");
                    StringBuilder result = new StringBuilder();
                    ResultSet resultSet = statement.executeQuery();
                    while (resultSet.next()) {
                        result.append(resultSet.getString("shortURL")).append(" ")
                                .append(resultSet.getString("longURL")).append("\n");
                    }
                    BufferedOutputStream dataOut = new BufferedOutputStream(clientConnect.getOutputStream());
                    dataOut.write(result.toString().getBytes());
                    dataOut.flush();
                    dataOut.close();
                } else if (Objects.equals(tokens[0], "POST")) {
                    String data;
                    try {
                        while ((data = in.readLine()) != null) {
                            String[] dataTokens = data.split(" ");
                            String shortURL = dataTokens[0];
                            String longURL = dataTokens[1];
                            save(shortURL, longURL, dbConnect);
                        }
                    } catch (Exception ignore) {
                    }
                } else if (Objects.equals(tokens[0], "DELETE")) {
                    String shortURLStart = tokens[1];
                    PreparedStatement statement = dbConnect.prepareStatement("DELETE FROM URL WHERE shortURL LIKE ?");
                    statement.setString(1, shortURLStart);
                    statement.executeUpdate();
                }
                clientConnect.close();
            }

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static class Handler extends Thread {
        Connection dbConnect;
        Socket clientConnect;
        BufferedReader in = null;
        PrintWriter out = null;
        BufferedOutputStream dataOut = null;

        LRUCacheSync<String, String> LRUCache;

        Handler(Socket clientConnect, Connection dbConnect, LRUCacheSync<String, String> LRUCache) {
            this.LRUCache = LRUCache;
            this.clientConnect = clientConnect;
            this.dbConnect = dbConnect;
        }

        public void run() {
            try {
                InputStream streamFromClient = clientConnect.getInputStream();
                OutputStream streamToClient = clientConnect.getOutputStream();
                in = new BufferedReader(new InputStreamReader(streamFromClient));
                out = new PrintWriter(streamToClient);
                dataOut = new BufferedOutputStream(streamToClient);

                String input = in.readLine();

                if (verbose) {
                    System.out.println("first line: " + input);
                }
                System.out.println("\nInput: " + input);

                Pattern pput = Pattern.compile("^PUT\\s+/\\?short=(\\S+)&long=(\\S+)\\s+(\\S+)$");
                Matcher mput = pput.matcher(input.toString());
                if (mput.matches()) {

                    String shortResource = mput.group(1);
                    String longResource = mput.group(2);
                    String httpVersion = mput.group(3);
                    System.out.println("shortResource: " + shortResource);
                    System.out.println("longResource: " + shortResource);

                    save(shortResource, longResource, this.dbConnect);

                    // Update cache with the new (shortResource, longResource) pair
                    if (!Objects.equals(this.LRUCache.getValue(shortResource), longResource)) {
                        this.LRUCache.putValue(shortResource, longResource);
                    }

                    File file = new File(WEB_ROOT, REDIRECT_RECORDED);
                    int fileLength = (int) file.length();
                    String contentMimeType = "text/html";
                    // read content to return to client
                    byte[] fileData = readFileData(file, fileLength);

                    out.println("HTTP/1.1 200 OK");
                    setHttpResponseHeaders(fileLength, contentMimeType, fileData);
                } else {
                    Pattern pget = Pattern.compile("^(\\S+)\\s+/(\\S+)\\s+(\\S+)$");
                    Matcher mget = pget.matcher(input.toString());
                    if (mget.matches()) {

                        String method = mget.group(1);
                        String shortResource = mget.group(2);
                        String httpVersion = mget.group(3);
                        System.out.println("shortResource: " + shortResource);

                        String longResource = this.LRUCache.getValue(shortResource);
                        System.out.println("Cache longResource: " + longResource);

                        if (longResource == null) {
                            longResource = find(shortResource, this.dbConnect);
                            this.LRUCache.putValue(shortResource, longResource);
                            System.out.println("DB longResource: " + longResource);
                        }

                        if (longResource != null) {
                            File file = new File(WEB_ROOT, REDIRECT);
                            int fileLength = (int) file.length();
                            String contentMimeType = "text/html";

                            // read content to return to client
                            byte[] fileData = readFileData(file, fileLength);

                            // out.println("HTTP/1.1 301 Moved Permanently");
                            out.println("HTTP/1.1 307 Temporary Redirect");
                            out.println("Location: " + longResource);
                            setHttpResponseHeaders(fileLength, contentMimeType, fileData);
                        } else {
                            File file = new File(WEB_ROOT, FILE_NOT_FOUND);
                            int fileLength = (int) file.length();
                            String content = "text/html";
                            byte[] fileData = readFileData(file, fileLength);

                            out.println("HTTP/1.1 404 File Not Found");
                            setHttpResponseHeaders(fileLength, content, fileData);
                        }
                    }
                }
            } catch (Exception e) {
                System.err.println("Server error: " + e.getMessage());
            } finally {
                try {
                    in.close();
                    out.close();
                    if (clientConnect != null) {
                        clientConnect.close(); // we close socket connection
                    }
                } catch (Exception e) {
                    System.err.println("Error closing stream : " + e.getMessage());
                }

                if (verbose) {
                    System.out.println("Connection closed.\n");
                }
            }
        }

        private void setHttpResponseHeaders(int fileLength, String contentMimeType, byte[] fileData)
                throws IOException {
            out.println("Server: Java HTTP Server/Shortener : 1.0");
            out.println("Date: " + new Date());
            out.println("Content-type: " + contentMimeType);
            out.println("Content-length: " + fileLength);
            out.println();
            out.flush();

            dataOut.write(fileData, 0, fileLength);
            dataOut.flush();
        }
    }
}
