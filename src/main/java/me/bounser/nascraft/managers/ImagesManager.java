package me.bounser.nascraft.managers;

import me.bounser.nascraft.Nascraft;
import me.bounser.nascraft.config.Config;
import org.bukkit.configuration.file.FileConfiguration;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;

public class ImagesManager {

    private static ImagesManager instance;

    public static ImagesManager getInstance() { return instance == null ? instance = new ImagesManager() : instance; }


    public BufferedImage getImage(String identifier) {

        FileConfiguration items = Config.getInstance().getItemsFileConfiguration();

        BufferedImage image = null;
        String imageName;
        String imagePath = Nascraft.getInstance().getDataFolder().getPath() + "/images/" + identifier + ".png";

        try (InputStream input = Files.newInputStream(new File(imagePath).toPath())) {

            image = ImageIO.read(input);

        } catch (IOException ignored) {
            // No image specified.
        } catch (IllegalArgumentException e) {
            Nascraft.getInstance().getLogger().info("Invalid argument for image: " + identifier);
        }

        if (image != null) return image;

        if (items.contains("items." + identifier + ".item-stack.type")) {

            String materialType = items.getString("items." + identifier + ".item-stack.type").toLowerCase();
            imageName = materialType + ".png";
            imagePath = "1-21-4-materials/minecraft_" + imageName;

            try (InputStream input = Nascraft.getInstance().getResource(imagePath)) {
                if (input != null) {
                    image = ImageIO.read(input);
                }
            } catch (IOException | IllegalArgumentException ignored) {}

            if (image != null) return image;

            // Fallback: download from Mojang's official texture CDN
            image = MojangTextureProvider.getInstance().getTexture(materialType);
            return image;
        }

        imageName = identifier.replaceAll("\\d", "").toLowerCase() + ".png";
        imagePath = "1-21-4-materials/minecraft_" + imageName;

        try (InputStream input = Nascraft.getInstance().getResource(imagePath)) {
            if (input != null) {
                image = ImageIO.read(input);
            }
        } catch (IOException | IllegalArgumentException ignored) {}

        if (image != null) return image;

        // Fallback: download from Mojang's official texture CDN
        String materialName = identifier.replaceAll("\\d", "").toLowerCase();
        image = MojangTextureProvider.getInstance().getTexture(materialName);

        return image;
    }

    public static byte[] getBytesOfImage(BufferedImage image) {
        ByteArrayOutputStream baosBalance = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, "png", baosBalance);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return baosBalance.toByteArray();
    }

}
