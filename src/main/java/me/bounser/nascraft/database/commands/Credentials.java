package me.bounser.nascraft.database.commands;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public class Credentials {

    public static void saveCredentials(Connection connection, String userName, String hash) {
        try {
            String sql1 = "SELECT id FROM credentials WHERE username=?;";
            PreparedStatement prep1 = connection.prepareStatement(sql1);
            prep1.setString(1, userName);
            ResultSet resultSet = prep1.executeQuery();

            if (resultSet.next()) {
                String sql2 = "UPDATE credentials SET hash=? WHERE username=?;";
                PreparedStatement prep2 = connection.prepareStatement(sql2);
                prep2.setString(1, hash);
                prep2.setString(2, userName);
                prep2.executeUpdate();
            } else {
                String sql2 = "INSERT INTO credentials (username, hash) VALUES (?, ?);";
                PreparedStatement prep2 = connection.prepareStatement(sql2);
                prep2.setString(1, userName);
                prep2.setString(2, hash);
                prep2.executeUpdate();
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public static String getHashFromUserName(Connection connection, String userName) {
        try {
            String sql = "SELECT hash FROM credentials WHERE username=?;";
            PreparedStatement prep = connection.prepareStatement(sql);
            prep.setString(1, userName);
            ResultSet resultSet = prep.executeQuery();

            if (resultSet.next()) {
                return resultSet.getString("hash");
            } else {
                return null;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public static void clearUserCredentials(Connection connection, String userName) {
        try {
            String sql = "DELETE FROM credentials WHERE username=?;";
            PreparedStatement prep = connection.prepareStatement(sql);
            prep.setString(1, userName);
            prep.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

}
