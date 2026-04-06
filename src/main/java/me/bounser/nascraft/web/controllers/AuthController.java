package me.bounser.nascraft.web.controllers;

import com.google.gson.Gson;
import io.javalin.Javalin;
import io.javalin.http.HttpStatus;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpSession;
import me.bounser.nascraft.config.Config;
import me.bounser.nascraft.database.Database;
import me.bounser.nascraft.web.CodesManager;
import me.bounser.nascraft.web.WebServerManager.*;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.java.JavaPlugin;
import org.mindrot.jbcrypt.BCrypt;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

public class AuthController {

    private static final String DISCORD_API_BASE = "https://discord.com/api/v10";

    public static void register(Javalin webServer, JavaPlugin plugin, Database database, HttpClient httpClient, Gson gson, CodesManager codesManager) {

        webServer.get("/api/code-time/{code}", ctx -> {
            String codeStr = ctx.pathParam("code");
            try {
                int code = Integer.parseInt(codeStr);
                long time = codesManager.getEpochTimeOfCode(code);
                if (time == 0) {
                    ctx.status(HttpStatus.NOT_FOUND).json(new StatusResponse("Code not found or expired."));
                    return;
                }
                ctx.json(time);
            } catch (NumberFormatException e) {
                ctx.status(HttpStatus.BAD_REQUEST).json(new StatusResponse("Invalid code format."));
            }
        });
        webServer.get("/api/code-expiration", ctx -> {
            int minutes = Config.getInstance().getWebCodeExpiration();
            ctx.json(minutes);
        });

        // --- Minecraft Authentication ---
        webServer.post("/api/login", ctx -> {
            LoginRequest loginReq = ctx.bodyAsClass(LoginRequest.class);
            if (loginReq.getUsername() == null || loginReq.getUsername().isBlank() ||
                    loginReq.getPassword() == null || loginReq.getPassword().isBlank()) {
                ctx.status(HttpStatus.BAD_REQUEST).json(new StatusResponse("Username and password are required."));
                return;
            }

            String storedHash = database.retrieveHash(loginReq.getUsername());

            if (storedHash == null) {
                ctx.status(HttpStatus.UNAUTHORIZED).json(new StatusResponse("Invalid username or password."));
                return;
            }

            if (BCrypt.checkpw(loginReq.getPassword(), storedHash)) {
                OfflinePlayer player = Bukkit.getOfflinePlayer(loginReq.getUsername());
                UUID playerUUID = player.getUniqueId();

                if (playerUUID == null) {
                    plugin.getLogger().warning("Could not resolve UUID for username: " + loginReq.getUsername() + " during web login.");
                    ctx.status(HttpStatus.INTERNAL_SERVER_ERROR).json(new StatusResponse("Could not verify player identity."));
                    return;
                }

                HttpSession session = ctx.req().getSession(true);
                String actualUsername = player.getName() != null ? player.getName() : loginReq.getUsername();
                session.setAttribute("minecraft-uuid", playerUUID.toString());
                session.setAttribute("minecraft-user-name", actualUsername);
                session.setMaxInactiveInterval(Config.getInstance().getWebTimeout() * 60);

                String discordUserId = database.getDiscordUserId(playerUUID);
                if (discordUserId != null) {
                    session.setAttribute("discord-user-id", discordUserId);
                    String discordNickname = database.getNicknameFromUserId(discordUserId);
                    session.setAttribute("discord-user-nickname", discordNickname != null ? discordNickname : "Linked Discord");
                }

                plugin.getLogger().info("User logged in via Minecraft: " + actualUsername);
                ctx.json(new LoginSuccessResponse(true, actualUsername));

            } else {
                ctx.status(HttpStatus.UNAUTHORIZED).json(new StatusResponse("Invalid username or password."));
            }
        });

        // --- Discord Related Endpoints ---
        webServer.get("/api/discord-client-id", ctx -> {
            String clientId = Config.getInstance().getDiscordId();
            if (clientId == null || clientId.isBlank()) {
                ctx.status(HttpStatus.INTERNAL_SERVER_ERROR).json(new StatusResponse("Discord integration not configured."));
                return;
            }
            ctx.json(new DiscordClientIdResponse(clientId));
        });

        webServer.get("/api/auth/discord/login", ctx -> {
            String clientId = Config.getInstance().getDiscordId();
            if (clientId == null || clientId.isBlank()) {
                ctx.status(HttpStatus.INTERNAL_SERVER_ERROR).json(new StatusResponse("Discord login is not configured."));
                return;
            }
            String state = generateSecureRandomString(32);
            HttpSession session = ctx.req().getSession(true);
            session.setAttribute("oauth-state", state);
            session.setMaxInactiveInterval(Config.getInstance().getWebTimeout() * 60);
            String redirectUri = ctx.scheme() + "://" + ctx.host() + "/auth/discord/callback";
            String encodedRedirectUri = URLEncoder.encode(redirectUri, StandardCharsets.UTF_8);
            String scope = URLEncoder.encode("identify email", StandardCharsets.UTF_8);
            String discordAuthUrl = DISCORD_API_BASE + "/oauth2/authorize?client_id=" + clientId +
                    "&redirect_uri=" + encodedRedirectUri + "&response_type=code&scope=" + scope + "&state=" + state;
            ctx.redirect(discordAuthUrl, HttpStatus.FOUND);
        });

        webServer.get("/auth/discord/callback", ctx -> {
            String code = ctx.queryParam("code");
            String state = ctx.queryParam("state");
            HttpSession session = ctx.req().getSession(false);
            String storedState = (session != null) ? (String) session.getAttribute("oauth-state") : null;

            if (state == null || storedState == null || !state.equals(storedState)) {
                ctx.status(HttpStatus.BAD_REQUEST).json(new StatusResponse("Invalid state."));
                if (session != null) session.removeAttribute("oauth-state");
                return;
            }
            if (session != null) session.removeAttribute("oauth-state");
            if (code == null) {
                ctx.status(HttpStatus.BAD_REQUEST).json(new StatusResponse("Code missing."));
                return;
            }

            String clientId = Config.getInstance().getDiscordId();
            String clientSecret = Config.getInstance().getDiscordSecret();
            String redirectUri = ctx.scheme() + "://" + ctx.host() + "/auth/discord/callback";

            String tokenRequestBody = "client_id=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8) +
                    "&client_secret=" + URLEncoder.encode(clientSecret, StandardCharsets.UTF_8) +
                    "&grant_type=authorization_code&code=" + URLEncoder.encode(code, StandardCharsets.UTF_8) +
                    "&redirect_uri=" + URLEncoder.encode(redirectUri, StandardCharsets.UTF_8);
            HttpRequest tokenRequest = HttpRequest.newBuilder()
                    .uri(URI.create(DISCORD_API_BASE + "/oauth2/token"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(tokenRequestBody)).build();
            try {
                HttpResponse<String> tokenResponse = httpClient.send(tokenRequest, HttpResponse.BodyHandlers.ofString());
                com.google.gson.JsonObject tokenJson = gson.fromJson(tokenResponse.body(), com.google.gson.JsonObject.class);
                if (tokenResponse.statusCode() != 200 || !tokenJson.has("access_token")) {
                    ctx.status(HttpStatus.INTERNAL_SERVER_ERROR).json(new StatusResponse("Discord token exchange failed."));
                    return;
                }
                String accessToken = tokenJson.get("access_token").getAsString();
                HttpRequest userRequest = HttpRequest.newBuilder()
                        .uri(URI.create(DISCORD_API_BASE + "/users/@me"))
                        .header("Authorization", "Bearer " + accessToken).GET().build();
                HttpResponse<String> userResponse = httpClient.send(userRequest, HttpResponse.BodyHandlers.ofString());
                com.google.gson.JsonObject userJson = gson.fromJson(userResponse.body(), com.google.gson.JsonObject.class);
                if (userResponse.statusCode() != 200 || !userJson.has("id")) {
                    ctx.status(HttpStatus.INTERNAL_SERVER_ERROR).json(new StatusResponse("Failed to fetch Discord user."));
                    return;
                }

                String discordUserId = userJson.get("id").getAsString();
                String discordGlobalName = userJson.has("global_name") && !userJson.get("global_name").isJsonNull() ? userJson.get("global_name").getAsString() : userJson.get("username").getAsString();
                String displayNickname = discordGlobalName;

                HttpSession currentSession = ctx.req().getSession(true);
                String minecraftUuidFromSessionStr = (String) currentSession.getAttribute("minecraft-uuid");

                if (minecraftUuidFromSessionStr != null) {
                    UUID minecraftUUID = UUID.fromString(minecraftUuidFromSessionStr);
                    UUID discordAlreadyLinkedToMcUUID = database.getUUIDFromUserid(discordUserId);
                    if (discordAlreadyLinkedToMcUUID != null && !discordAlreadyLinkedToMcUUID.equals(minecraftUUID)) {
                        ctx.redirect("/?error=discord_already_linked_other", HttpStatus.FOUND);
                        return;
                    }
                    database.saveDiscordLink(minecraftUUID, discordUserId, displayNickname);
                    currentSession.setAttribute("discord-user-id", discordUserId);
                    currentSession.setAttribute("discord-user-nickname", displayNickname);
                    ctx.redirect("/?link_status=success", HttpStatus.FOUND);
                } else {
                    UUID existingMinecraftUUID = database.getUUIDFromUserid(discordUserId);
                    if (existingMinecraftUUID != null) {
                        OfflinePlayer player = Bukkit.getOfflinePlayer(existingMinecraftUUID);
                        String minecraftUsername = player.getName() != null ? player.getName() : database.getNicknameFromUserId(discordUserId);
                        if (minecraftUsername == null) {
                            ctx.status(HttpStatus.INTERNAL_SERVER_ERROR).json(new StatusResponse("Error retrieving linked MC account."));
                            return;
                        }
                        currentSession.setAttribute("minecraft-uuid", existingMinecraftUUID.toString());
                        currentSession.setAttribute("minecraft-user-name", minecraftUsername);
                        currentSession.setAttribute("discord-user-id", discordUserId);
                        currentSession.setAttribute("discord-user-nickname", displayNickname);
                        ctx.redirect("/", HttpStatus.FOUND);
                    } else {
                        currentSession.setAttribute("pending-discord-user-id", discordUserId);
                        currentSession.setAttribute("pending-discord-user-nickname", displayNickname);
                        String linkingCode = String.valueOf(codesManager.generateCode(discordUserId, displayNickname));
                        if (linkingCode == null || "null".equals(linkingCode)) {
                            ctx.status(HttpStatus.INTERNAL_SERVER_ERROR).json(new StatusResponse("Failed to generate linking code."));
                            return;
                        }
                        ctx.redirect("/?view=linkAccount&code=" + URLEncoder.encode(linkingCode, StandardCharsets.UTF_8) +
                                "&user=" + URLEncoder.encode(discordUserId, StandardCharsets.UTF_8) +
                                "&displayName=" + URLEncoder.encode(displayNickname, StandardCharsets.UTF_8), HttpStatus.FOUND);
                    }
                }
            } catch (IOException | InterruptedException e) {
                Thread.currentThread().interrupt();
                ctx.status(HttpStatus.INTERNAL_SERVER_ERROR).json(new StatusResponse("Discord auth error."));
            }
        });

        webServer.get("/api/auth/status", ctx -> {
            HttpSession session = ctx.req().getSession(false);
            if (session == null) {
                ctx.json(new AuthStatusResponse("unauthenticated", null, null, null, null, null, "Not authenticated."));
                return;
            }
            String minecraftUuidStr = (String) session.getAttribute("minecraft-uuid");
            String minecraftUsername = (String) session.getAttribute("minecraft-user-name");
            String discordUserIdInSession = (String) session.getAttribute("discord-user-id");
            String discordNicknameInSession = (String) session.getAttribute("discord-user-nickname");

            if (minecraftUuidStr != null) {
                UUID mcUUID = UUID.fromString(minecraftUuidStr);
                String actualDiscordUserId = database.getDiscordUserId(mcUUID);
                String actualDiscordNickname = null;
                if (actualDiscordUserId != null) {
                    actualDiscordNickname = database.getNicknameFromUserId(actualDiscordUserId);
                    if(actualDiscordNickname == null && discordNicknameInSession !=null && actualDiscordUserId.equals(discordUserIdInSession)) {
                        actualDiscordNickname = discordNicknameInSession;
                    } else if (actualDiscordNickname == null) {
                        actualDiscordNickname = "Linked Discord";
                    }
                    if(!actualDiscordUserId.equals(discordUserIdInSession) || (actualDiscordNickname != null && !actualDiscordNickname.equals(discordNicknameInSession))) {
                        session.setAttribute("discord-user-id", actualDiscordUserId);
                        session.setAttribute("discord-user-nickname", actualDiscordNickname);
                    }
                }
                ctx.json(new AuthStatusResponse("minecraft_authenticated", minecraftUsername, actualDiscordUserId, actualDiscordNickname, actualDiscordUserId != null ? "linked" : "not_linked", null, "User authenticated via Minecraft."));
            } else {
                String pendingDiscordUserId = (String) session.getAttribute("pending-discord-user-id");
                String pendingDiscordNickname = (String) session.getAttribute("pending-discord-user-nickname");
                if (pendingDiscordUserId != null) {
                    String linkingCode = String.valueOf(codesManager.getCodeForDiscordUser(pendingDiscordUserId));
                    if ("null".equals(linkingCode)) linkingCode = null;
                    ctx.json(new AuthStatusResponse("discord_auth_pending_minecraft_link", null, pendingDiscordUserId, pendingDiscordNickname, null, linkingCode, "Discord authenticated. Minecraft account linking is pending."));
                } else {
                    ctx.json(new AuthStatusResponse("unauthenticated", null, null, null, null, null, "Not authenticated."));
                }
            }
        });

        webServer.post("/api/discord/unlink", ctx -> {
            HttpSession session = ctx.req().getSession(false);
            if (session == null || session.getAttribute("minecraft-uuid") == null) {
                ctx.status(HttpStatus.UNAUTHORIZED).json(new StatusResponse("Unauthorized"));
                return;
            }
            String minecraftUuidStr = (String) session.getAttribute("minecraft-uuid");
            UUID minecraftUUID = UUID.fromString(minecraftUuidStr);
            database.removeDiscordLink(minecraftUUID);
            session.removeAttribute("discord-user-id");
            session.removeAttribute("discord-user-nickname");
            plugin.getLogger().info("User " + session.getAttribute("minecraft-user-name") + " unlinked their Discord account.");
            ctx.json(new StatusResponse("Discord account unlinked successfully."));
        });

        webServer.post("/api/logout", ctx -> {
            HttpSession session = ctx.req().getSession(false);
            if (session != null) session.invalidate();
            Cookie cookie = new Cookie("JSESSIONID", "");
            cookie.setMaxAge(0);
            cookie.setPath("/");
            ctx.res().addCookie(cookie);
            ctx.json(new StatusResponse("Logged out successfully."));
        });
    }

    private static String generateSecureRandomString(int length) {
        SecureRandom random = new SecureRandom();
        byte[] bytes = new byte[(length * 6 + 7) / 8];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
