import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;

public class ImportDB {
    public static void main(String[] args) throws SQLException, IOException {

        String url = "jdbc:sqlite:database.sqlite";
        String createTable = "CREATE TABLE IF NOT EXISTS URL (shortURL TEXT PRIMARY KEY, longURL TEXT);";

        Connection connection = DriverManager.getConnection(url);

        if (connection != null) {
            connection.createStatement().execute(createTable);

            FileReader fr = new FileReader("./database.txt");
            BufferedReader br = new BufferedReader(fr);

            String line;
            PreparedStatement statement = connection.prepareStatement("INSERT INTO URL (shortURL, longURL) VALUES (?, ?)");

            while ((line = br.readLine()) != null) {
                String[] urls = line.split("\t");
                statement.setString(1, urls[0]);
                statement.setString(2, urls[1]);
                statement.executeUpdate();
                System.out.println(line);
            }
        }
    }
}
