import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * runs a single-threaded proxy server on
 * the specified local port. It never returns.
 */
public class RunServer extends Thread {
    private final Map<String, String> hostMapping;
    private Socket server = null;
    private Socket client;
    private final int remoteport = 8080;

    RunServer(Socket client, Map<String, String> HostMapping) {
        this.client = client;
        this.hostMapping = HostMapping;
    }

    public void run() {
        byte[] reply = new byte[4096];

        // Make a connection to the real server.
        try {
            // Get client streams.
            final InputStream streamFromClient = client.getInputStream();
            final OutputStream streamToClient = client.getOutputStream();

            BufferedReader streamFromClientBuffer = new BufferedReader(new InputStreamReader(streamFromClient));
            BufferedOutputStream streamToClientBuffer = new BufferedOutputStream(streamToClient);

            String input = streamFromClientBuffer.readLine();

            if (input == null) return;

            Pattern pput = Pattern.compile("^PUT\\s+/\\?short=(\\S+)&long=(\\S+)\\s+(\\S+)$");
            Matcher mput = pput.matcher(input);

            Pattern pget = Pattern.compile("^(\\S+)\\s+/(\\S+)\\s+(\\S+)$");
            Matcher mget = pget.matcher(input);

            String shortResource;
            String longResource;

            if (mput.matches()) {
                shortResource = mput.group(1);
                longResource = mput.group(2);

                System.out.println("--- Received PUT Request ---");
                System.out.println("Short URL: " + shortResource);
                System.out.println("Long URL: " + longResource);
            }
            else if (mget.matches()) {
                shortResource = mget.group(2);

                System.out.println("--- Received GET Request ---");
                System.out.println("Short URL: " + shortResource);
            }
            else {
                System.out.println("Error: Unknown http request");
                System.out.println("Thread exited");
                return;
            }

            String firstChar = String.valueOf(shortResource.charAt(0));
            String host = hostMapping.get(firstChar);

            // Connect to URL Server
            try {
                server = new Socket(host, remoteport);
            } catch (Exception e) {
                HostWatcher.serverCrashedNotifier(host);
                return;
            }

            final InputStream streamFromServer = server.getInputStream();
            final OutputStream streamToServer = server.getOutputStream();
            BufferedReader streamFromServerBuffer = new BufferedReader(new InputStreamReader(streamFromServer));
            BufferedOutputStream streamToServerBuffer = new BufferedOutputStream(streamToServer);

            // Send request to URL Server
            String EOF = "\n";
            streamToServerBuffer.write((input + EOF).getBytes());
            streamToServerBuffer.flush();

            int bytesRead;
            try {
                boolean printHttpStatus = true;

                while ((bytesRead = streamFromServer.read(reply)) != -1) {
                    if (printHttpStatus) {
                        String httpStatus = new String(reply, StandardCharsets.UTF_8);
                        System.out.println(httpStatus.substring(0, httpStatus.indexOf("\n")));
                    }
                    streamToClientBuffer.write(reply, 0, bytesRead);
                    streamToClientBuffer.flush();
                    printHttpStatus = false;
                }
            } catch (IOException e) {
                System.out.println("Proxy Error: " + e.getMessage());
            }

            streamToServerBuffer.close();
            streamToClientBuffer.close();
            streamFromServerBuffer.close();
            streamFromClientBuffer.close();

        } catch (IOException e) {
            System.out.println(e);
        } finally {
            try {
                if (server != null)
                    server.close();
                if (client != null)
                    client.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            String threadName = Thread.currentThread().getName();
            long threadID = Thread.currentThread().threadId();

            System.out.println("Thread: " + threadName + " with ID " + threadID + " exit successfully.\n");
        }
    }
}