package me.bounser.nascraft.web;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

public class CodesManager {

    private static CodesManager instance;
    private final Map<Integer, CodeInfo> codes = new HashMap<>();
    private final Map<String, Integer> discordToCode = new HashMap<>();
    private final Random random = new Random();

    private CodesManager() {}

    public static CodesManager getInstance() {
        if (instance == null) instance = new CodesManager();
        return instance;
    }

    public int generateCode(String discordUserId, String nickname) {
        int code = 100000 + random.nextInt(900000);
        CodeInfo info = new CodeInfo(discordUserId, nickname, System.currentTimeMillis());
        codes.put(code, info);
        discordToCode.put(discordUserId, code);
        return code;
    }

    public long getEpochTimeOfCode(int code) {
        CodeInfo info = codes.get(code);
        return info != null ? info.timestamp : 0;
    }

    public int getCodeForDiscordUser(String discordUserId) {
        return discordToCode.getOrDefault(discordUserId, 0);
    }

    public String getDiscordUserId(int code) {
        CodeInfo info = codes.get(code);
        return info != null ? info.discordUserId : null;
    }

    private static class CodeInfo {
        String discordUserId;
        String nickname;
        long timestamp;

        CodeInfo(String discordUserId, String nickname, long timestamp) {
            this.discordUserId = discordUserId;
            this.nickname = nickname;
            this.timestamp = timestamp;
        }
    }
}
