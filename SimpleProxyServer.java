import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.nio.file.*;

public class SimpleProxyServer {

    static final int NUM_THREADS = 8;
    static final ThreadPoolExecutor threadPool = (ThreadPoolExecutor) Executors.newFixedThreadPool(NUM_THREADS);
    static final Path path = Paths.get("./hosts.conf");
    static final Map<String, String> hostMapping = Collections.synchronizedMap(new LinkedHashMap<>());

    public static void main(String[] args) {

        int localport = 8081;
        System.out.println("Starting proxy on port " + localport);

        Thread HostWatcherThread = new Thread(new HostWatcher(path, hostMapping));
        HostWatcherThread.start();
        System.out.println("Launched HostWatcher");

        try (ServerSocket ss = new ServerSocket(localport)) {
            // Start listening
            while (true) {
                threadPool.execute(new RunServer(ss.accept(), hostMapping));
            }
        } catch (IOException e) {
            System.err.println("Could not listen on port " + localport);
            System.exit(-1);
        }
    }
}


