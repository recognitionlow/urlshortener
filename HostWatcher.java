import java.io.*;
import java.net.Socket;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;

// Reference: https://stackoverflow.com/questions/63731952/java-nio-watchservice
public class HostWatcher implements Runnable {

    private final Path path;
    private List<String> hosts = Collections.synchronizedList(new ArrayList<>());
    private final String filename;
    private final Map<String, String> hostMapping;
    private boolean isInitialized = false;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    public HostWatcher(Path path, Map<String, String> hostMapping) {
        this.path = path;
        this.filename = path.getFileName().toString();
        this.hostMapping = hostMapping;
        reloadHosts();
        scheduler.scheduleAtFixedRate(() -> dbBackup(hosts), 0, 60, TimeUnit.SECONDS);
    }

    @Override
    public void run() {
        WatchService watchService = null;
        try {
            watchService = FileSystems.getDefault().newWatchService();
            path.getParent().register(watchService, ENTRY_MODIFY);
        } catch (IOException e) {
            System.out.println("HostWatcher Error: " + e.getMessage());
        }

        while (true) {
            WatchKey key = null;
            try {
                key = watchService.take();
            } catch (InterruptedException e) {
                System.out.println("HostWatcher Error: " + e.getMessage());
            }
            if (key == null)
                break;

            for (WatchEvent<?> event : key.pollEvents()) {
                String fileChanged = event.context().toString();
                if (event.kind() == ENTRY_MODIFY && fileChanged.equals(this.filename)) {
                    System.out.println("Modified File: " + fileChanged);
                    System.out.println("Target Host File: " + this.filename);
                    reloadHosts();
                }
            }
            key.reset();

        }

        System.out.println("HostWatcher Critical Error: HostWatcher stopped");
    }

    synchronized private void reloadHosts() {
        try {
            TimeUnit.SECONDS.sleep(10);
        } catch (InterruptedException e) {
            System.out.println("HostWatcher sleep interrupted: " + e.getMessage());
        }

        hosts.clear();
        try {
            hosts = Files.readAllLines(path);
        } catch (IOException e) {
            e.printStackTrace();
        }

        int hostSize = hosts.size();
        char[] alphabet = "abcdefghijklmnopqrstuvwxyz0123456789".toCharArray();
        int avgMappingLen = 36 / hostSize;
        HashMap<String, String> newHostMapping = new HashMap<>();

        // evenly continuously distribute the alphabet to hosts
        for (int i = 0; i < hostSize; i++) {
            int start = i * avgMappingLen;
            int end = (i == hostSize - 1) ? 36 : (i + 1) * avgMappingLen;

            for (int j = start; j < end; j++) {
                newHostMapping.put(String.valueOf(alphabet[j]), hosts.get(i));
            }
        }

        if (!isInitialized) {
            hostMapping.putAll(newHostMapping);
            isInitialized = true;
        } else {
            migrateData(newHostMapping);
            hostMapping.clear();
            hostMapping.putAll(newHostMapping);
        }
        System.out.println("Hosts reloaded: " + hosts + "\n");
    }

    private void migrateData(HashMap<String, String> newHostMapping) {
        System.out.println("Start migration");
        for (Map.Entry<String, String> entry : hostMapping.entrySet()) {
            String key = entry.getKey();
            String oldHost = entry.getValue();
            String newHost = newHostMapping.get(key);
            if (!oldHost.equals(newHost)) {
                try {
                    Socket oldServer;
                    try {
                        oldServer = new Socket(oldHost, 65535);
                    } catch (Exception e) {
                        serverCrashedHandler(oldHost, newHost, key);
                        return;
                    }

                    Socket newServer = new Socket(newHost, 65535);
                    BufferedOutputStream oldOut = new BufferedOutputStream(oldServer.getOutputStream());
                    BufferedOutputStream newOut = new BufferedOutputStream(newServer.getOutputStream());
                    BufferedReader oldIn = new BufferedReader(new InputStreamReader(oldServer.getInputStream()));

                    oldOut.write(("GET " + key + "\n").getBytes());
                    oldOut.flush();
                    String oldResponse = oldIn.lines().collect(Collectors.joining("\n"));
                    oldIn.close();
                    oldServer.close();

                    // System.out.println(oldResponse);
                    newOut.write(("POST \n" + oldResponse + "\n").getBytes());
                    newOut.flush();

                    oldServer = new Socket(oldHost, 65535);
                    oldOut = new BufferedOutputStream(oldServer.getOutputStream());

                    oldOut.write(("DELETE " + key + "\n").getBytes());
                    oldOut.flush();
                    oldOut.close();
                    oldServer.close();

                    newOut.close();
                    newServer.close();
                } catch (IOException e) {
                    System.err.println("\\u001B[31m" + "Migration Error: " + e.getMessage() + "\\u001B[0m");
                }
            }
        }
        System.out.println("Finished migration");
    }

    private void dbBackup(List<String> hosts) {
        System.out.println("Start database backup");
        for (String host : hosts) {
            String command = String.format("rsync -avz %s:/virtual/%s.sqlite /virtual", host, host);
            ProcessBuilder processBuilder = new ProcessBuilder("/bin/bash", "-c", command);
            try {
                Process process = processBuilder.start();
                process.waitFor();
            } catch (IOException | InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    static public void serverCrashedNotifier(String hostname) {
        try {
            File file = new File("hosts.conf");
            FileReader fileReader = new FileReader(file);
            BufferedReader bufferedReader = new BufferedReader(fileReader);
            StringBuilder fileContent = new StringBuilder();
            String line;

            while ((line = bufferedReader.readLine()) != null) {
                fileContent.append(line.replace(hostname + "\n", "")).append("\n");
            }

            bufferedReader.close();
            fileReader.close();

            FileWriter fileWriter = new FileWriter(file);
            BufferedWriter bufferedWriter = new BufferedWriter(fileWriter);
            bufferedWriter.write(fileContent.toString());

            bufferedWriter.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void serverCrashedHandler(String oldHost, String newHost, String key) {
        try {
            // get data from backup
            Connection dbConnect = DriverManager.getConnection("jdbc:sqlite:/virtual/" + oldHost + ".sqlite");
            PreparedStatement statement = dbConnect.prepareStatement(
                    "SELECT longURL, shortURL FROM URL WHERE shortURL LIKE ?"
            );
            statement.setString(1, key + "%");
            StringBuilder result = new StringBuilder();
            ResultSet resultSet = statement.executeQuery();
            while (resultSet.next()) {
                result.append(resultSet.getString("shortURL")).append(" ")
                        .append(resultSet.getString("longURL")).append("\n");
            }
            resultSet.close();
            statement.close();
            dbConnect.close();

            // send data to new server
            Socket newServer = new Socket(newHost, 65535);
            BufferedOutputStream newOut = new BufferedOutputStream(newServer.getOutputStream());
            newOut.write(("POST \n" + result + "\n").getBytes());
            newOut.flush();
            newOut.close();
            newServer.close();
        } catch (Exception e) {
            e.printStackTrace();
        }

    }

}
