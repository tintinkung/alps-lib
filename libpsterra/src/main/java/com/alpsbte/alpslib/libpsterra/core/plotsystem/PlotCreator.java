package com.alpsbte.alpslib.libpsterra.core.plotsystem;

import com.alpsbte.alpslib.libpsterra.core.Connection;
import com.alpsbte.alpslib.libpsterra.core.DatabaseConnection;
import com.alpsbte.alpslib.libpsterra.core.config.ConfigPaths;
import com.alpsbte.alpslib.libpsterra.utils.FTPManager;
import com.alpsbte.alpslib.libpsterra.utils.Utils;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Logger;
import com.jcraft.jsch.Session;
import com.sk89q.worldedit.*;
import com.sk89q.worldedit.Vector;
import com.sk89q.worldedit.extent.clipboard.BlockArrayClipboard;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardWriter;
import com.sk89q.worldedit.function.operation.ForwardExtentCopy;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.regions.AbstractRegion;
import com.sk89q.worldedit.regions.CylinderRegion;
import com.sk89q.worldedit.regions.Polygonal2DRegion;
import com.sk89q.worldedit.regions.Region;


import org.apache.commons.lang.StringUtils;
import org.apache.commons.vfs2.FileObject;
import org.apache.commons.vfs2.FileSystemOptions;
import org.apache.commons.vfs2.impl.StandardFileSystemManager;
import org.apache.commons.vfs2.provider.ftp.FtpFileSystemConfigBuilder;
import org.apache.commons.vfs2.provider.sftp.SftpFileSystemConfigBuilder;
import org.bukkit.*;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Sign;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

public class PlotCreator {
    @FunctionalInterface
    public interface IPlotRegionsAction {
        void onSchematicsCreationComplete(Polygonal2DRegion plotRegion, CylinderRegion environmentRegion, Vector plotCenter);
    }

    public final static double PLOT_VERSION = 3.0;
    private final String schematicsPath;
    public final static int MIN_OFFSET_Y = 5;
    private Plugin plugin;
    private Connection connection;

    public PlotCreator(Plugin plugin, Connection connection){
        this.plugin = plugin;
        this.schematicsPath = Paths.get(plugin.getDataFolder().getAbsolutePath(), "schematics") + File.separator;
        this.connection = connection;
        
        //DEBUG!
        //testSFTPConnection_JSCH();//success
        //testSFTPConnection_VFS2();//success
    }

    public void create(Player player, int environmentRadius, IPlotRegionsAction plotRegionsAction) {
        Polygonal2DRegion plotRegion;
        Vector plotCenter;
        CylinderRegion environmentRegion = null;

        // Get WorldEdit selection of player
        Region rawPlotRegion;
        try {
            rawPlotRegion = Objects.requireNonNull(WorldEdit.getInstance().getSessionManager().findByName(player.getName())).getSelection(
                    Objects.requireNonNull(WorldEdit.getInstance().getSessionManager().findByName(player.getName())).getSelectionWorld());
        } catch (NullPointerException | IncompleteRegionException ex) {
            player.sendMessage(Utils.getErrorMessageFormat("Please select a plot using WorldEdit!", plugin.getConfig()));
            return;
        }

        // Create plot and environment regions
        // Check if WorldEdit selection is polygonal
        if (rawPlotRegion instanceof Polygonal2DRegion) {
            // Cast WorldEdit region to polygonal region
            plotRegion = (Polygonal2DRegion) rawPlotRegion;

            // Check if the polygonal region is valid
            if (plotRegion.getLength() > 100 || plotRegion.getWidth() > 100 || (plotRegion.getHeight() > 256 - MIN_OFFSET_Y)) {
                player.sendMessage(Utils.getErrorMessageFormat("Please adjust your selection size!", plugin.getConfig()));
                return;
            }

            // Get plot minY and maxY
            double offsetHeight = (256 - plotRegion.getHeight()) / 2d;
            final int minYOffset = plotRegion.getMinimumY() - (int) Math.ceil(offsetHeight);
            final int maxYOffset = plotRegion.getMaximumY() + (int) Math.floor(offsetHeight);
            final int minY = plotRegion.getMinimumY() - MIN_OFFSET_Y;
            final int maxY = maxYOffset + (int) Math.ceil(offsetHeight) - MIN_OFFSET_Y;

            plotRegion.setMinimumY(minY);
            plotRegion.setMaximumY(maxY);

            // Create the environment selection
            if (environmentRadius > 0) {
                // Get min region size for environment radius
                int radius = Math.max(plotRegion.getWidth() / 2 + environmentRadius, plotRegion.getLength() / 2 + environmentRadius);

                // Create a new cylinder region with the size of the plot + the configured radius around it
                Vector plotRegionCenter = plotRegion.getCenter();
                environmentRegion = new CylinderRegion(
                        plotRegion.getWorld(),
                        new Vector(Math.floor(plotRegionCenter.getX()), plotRegionCenter.getY(), Math.floor(plotRegionCenter.getZ())),
                        new Vector2D(radius, radius),
                        minY,
                        maxY
                );

                // Convert environment region to polygonal region and save points
                final List<BlockVector2D> environmentRegionPoints = environmentRegion.polygonize(-1);
                final AtomicInteger newYMin = new AtomicInteger(minY);

                // Iterate over the points and check for the lowest Y value
                final World world = player.getWorld();
                environmentRegionPoints.forEach(p -> {
                    int highestBlock = minYOffset;
                    for (int y = minYOffset; y <= maxYOffset; y++) {
                        if (world.getBlockAt(p.getBlockX(), y, p.getBlockZ()).getType() != Material.AIR) highestBlock = y;
                    }
                    if (highestBlock < newYMin.get()) newYMin.set(highestBlock);
                });

                // Update plot and environment min and max Y to new value if necessary
                if (newYMin.get() < minY) {
                    int heightDif = (minY - newYMin.get()) + MIN_OFFSET_Y;
                    plotRegion.setMinimumY(newYMin.get() - MIN_OFFSET_Y);
                    environmentRegion.setMinimumY(newYMin.get() - MIN_OFFSET_Y);
                    plotRegion.setMaximumY(maxY - heightDif);
                    environmentRegion.setMaximumY(maxY - heightDif);
                }
            }
            plotCenter = plotRegion.getCenter();
            plotRegionsAction.onSchematicsCreationComplete(plotRegion, environmentRegion, plotCenter);
        } else {
            player.sendMessage(Utils.getErrorMessageFormat("Please use polygonal selection to create a new plot!", plugin.getConfig()));
        }
    }

    public void createPlot(Player player, CityProject cityProject, int difficultyID) {
        CompletableFuture.runAsync(() -> {
            boolean environmentEnabled;

                // Read the config
                FileConfiguration config = plugin.getConfig();
                environmentEnabled = config.getBoolean(ConfigPaths.ENVIRONMENT_ENABLED);
                int environmentRadius = config.getInt(ConfigPaths.ENVIRONMENT_RADIUS);

                create(player, environmentEnabled ? environmentRadius : -1, (plotRegion, environmentRegion, plotCenter) -> {
                    int plotID = -1;;
                    String plotFilePath;
                    String environmentFilePath = null;

                    try {
                        // Check if selection contains sign
                        if (!containsSign(plotRegion, player.getWorld())) {
                            player.sendMessage(Utils.getErrorMessageFormat("Please place a minimum of one sign for the street side!", plugin.getConfig()));
                            return;
                        }


                        // Inform player about the plot creation
                        player.sendMessage(Utils.getInfoMessageFormat("Creating plot...", plugin.getConfig()));


                        // Convert polygon outline data to string
                        String polyOutline;
                        List<String> points = new ArrayList<>();

                        for (BlockVector2D point : plotRegion.getPoints())
                            points.add(point.getX() + "," + point.getZ());
                        polyOutline = StringUtils.join(points, "|");


                        // Insert into database (as transaction, needs to be committed or canceled to finalze)
                        
                        plotID = connection.createPlotTransaction(
                            cityProject, difficultyID, plotCenter,polyOutline, player, PLOT_VERSION);

                        // Save plot and environment regions to schematic files
                        // Get plot schematic file path
                        int serverID = connection.getServerID(cityProject);
                        plotFilePath = createPlotSchematic(plotRegion, Paths.get(schematicsPath, String.valueOf(serverID), String.valueOf(cityProject.id), plotID + ".schematic").toString());

                        if (plotFilePath == null) {
                            Bukkit.getLogger().log(Level.SEVERE, "Could not create plot schematic file!");
                            player.sendMessage(Utils.getErrorMessageFormat("An error occurred while creating plot!", plugin.getConfig()));
                            return;
                        }

                        // Get environment schematic file path
                        if (environmentEnabled) {
                            environmentFilePath = createPlotSchematic(environmentRegion, Paths.get(schematicsPath, String.valueOf(serverID), String.valueOf(cityProject.id), plotID + "-env.schematic").toString());

                            if (environmentFilePath == null) {
                                Bukkit.getLogger().log(Level.SEVERE, "Could not create environment schematic file!");
                                player.sendMessage(Utils.getErrorMessageFormat("An error occurred while creating plot!", plugin.getConfig()));
                                return;
                            }
                        }


                        // Upload schematic files to SFTP/FTP server if enabled
                        FTPConfiguration ftpConfiguration = connection.getFTPConfiguration(cityProject);

                        if (ftpConfiguration != null) {
                            if (environmentEnabled) FTPManager.uploadSchematics(FTPManager.getFTPUrl(ftpConfiguration, cityProject.id), new File(plotFilePath), new File(environmentFilePath));
                            else FTPManager.uploadSchematics(FTPManager.getFTPUrl(ftpConfiguration, cityProject.id), new File(plotFilePath));
                        }


                        // Place plot markings on plot region
                        placePlotMarker(plotRegion, player, plotID);
                        // TODO: Change top blocks of the plot region to mark plot as created


                        // Finalize database transaction
                        connection.commitPlot();
                        
                        player.sendMessage(Utils.getInfoMessageFormat("Successfully created new plot! §f(City: §6" + cityProject.name + " §f| Plot-ID: §6" + plotID + "§f)", plugin.getConfig()));
                        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);
                    } catch (Exception ex) {
                        try {
                            connection.rollbackPlot(plotID);
                            
                        } catch (Exception rollbackEx) {
                            Bukkit.getLogger().log(Level.SEVERE, "An exception occured during rollback!", rollbackEx);
                        }
                        Bukkit.getLogger().log(Level.SEVERE, "An error occurred while creating plot:" + ex.getMessage(), ex);
                        player.sendMessage(Utils.getErrorMessageFormat("An error occurred while creating plot: " + ex.getMessage(), plugin.getConfig()));
                    }
                });
        });
    }

    public void testSFTPConnection_JSCH() {

        //----------------Jsch direct ----------------

        //FTPManager.uploadSchematics(FTPManager.getFTPUrl(ftpConfiguration, cityProject.id), new File(plotFilePath));
        //StandardFileSystemManager fileManager = new StandardFileSystemManager();
        try {
            System.out.println("****************************************");
            FTPConfiguration ftpConfiguration = connection.getFTPConfiguration(3);
            JSch jsch = new JSch();
        
            Properties config = new Properties();
            config.put("cipher.s2c", 
                    "aes128-ctr,aes128-cbc,3des-ctr,3des-cbc,blowfish-cbc,aes192-ctr,aes192-cbc,aes256-ctr,aes256-cbc");
            config.put("cipher.c2s",
                    "aes128-ctr,aes128-cbc,3des-ctr,3des-cbc,blowfish-cbc,aes192-ctr,aes192-cbc,aes256-ctr,aes256-cbc");
            config.put("kex", "diffie-hellman-group1-sha1,diffie-hellman-group14-sha1,diffie-hellman-group-exchange-sha1,diffie-hellman-group-exchange-sha256");
            
            Session jschSession = jsch.getSession(ftpConfiguration.username,ftpConfiguration.address,ftpConfiguration.port);
            //FOR DEBUGGING ONLY: STRICTHOSTKEY = no
            //TODO : add target to known-hosts and include public key, see
            //https://stackoverflow.com/questions/2003419/com-jcraft-jsch-jschexception-unknownhostkey
            config.put("StrictHostKeyChecking", "no");
            jschSession.setPassword(ftpConfiguration.password);
            jschSession.setConfig(config);

            System.out.println("Testing JSCH sftp connect (ignoring host fingerprint, strong crpyto) to " + ftpConfiguration.address + " with user " + ftpConfiguration.username);
            Logger jschLogger = new Logger() {
                @Override
                public boolean isEnabled(int arg0){return true;}

                @Override
                public void log(int arg0, String arg1){
                    System.out.println("JSCH log: " + Integer.toString(arg0) + ": " +arg1);
                }
            };

            JSch.setLogger(jschLogger);
            jschSession.connect();
            jschSession.disconnect();
            System.out.println("JSCH Success!");
        } catch (Exception e) {
            System.out.println("SFTP JSCH TEST FAILED with " +e.getMessage());
            e.printStackTrace();
        } 
        
    }

    public void testSFTPConnection_VFS2() {


        //--- SSHJ --------------------
        try (StandardFileSystemManager fileManager = new StandardFileSystemManager()) {
            FTPConfiguration ftpConfiguration = connection.getFTPConfiguration(3);

            System.out.println("Testing VFS2 sftp connect connect to " + ftpConfiguration.address + " with user " + ftpConfiguration.username);
            

            fileManager.init();

            FileSystemOptions fileOptions = new FileSystemOptions();
            SftpFileSystemConfigBuilder.getInstance().setStrictHostKeyChecking(fileOptions, "no");
            SftpFileSystemConfigBuilder.getInstance().setPreferredAuthentications(fileOptions, "password");
            SftpFileSystemConfigBuilder.getInstance().setUserDirIsRoot(fileOptions, false);
            SftpFileSystemConfigBuilder.getInstance().setKeyExchangeAlgorithm(fileOptions, "diffie-hellman-group1-sha1,diffie-hellman-group14-sha1,diffie-hellman-group-exchange-sha1,diffie-hellman-group-exchange-sha256");
            //SftpFileSystemConfigBuilder.getInstance().set "aes128-ctr,aes128-cbc,3des-ctr,3des-cbc,blowfish-cbc,aes192-ctr,aes192-cbc,aes256-ctr,aes256-cbc");
            //we need to configure the ssh options to use stronger crypto
            
            FtpFileSystemConfigBuilder.getInstance().setPassiveMode(fileOptions, true);
            FtpFileSystemConfigBuilder.getInstance().setUserDirIsRoot(fileOptions, false);

            URI ftpURI = new URI("sftp",
                ftpConfiguration.username + ":" + ftpConfiguration.password,
                ftpConfiguration.address,
                ftpConfiguration.port,
                "/plugins/PlotSystem-Terra/schematics/test.txt",
                null,
                null);
            // Get remote path
            FileObject remote = fileManager.resolveFile(ftpURI.toString(), fileOptions);
            // BufferedInputStream inputStreamReader = new BufferedInputStream(remote.getContent().getInputStream());
            // String content = "";
            // while (inputStreamReader.available() > 0) {
            //     char c = (char) inputStreamReader.read();
            //     content = content.concat(String.valueOf(c));
            // }
            System.out.println("FTP testfile exists: " +Boolean.toString(remote.exists()));
            
        }
        catch (Exception e) {
            System.out.println("SFTP VFS2 TEST FAILED with " +e.getMessage());
            e.printStackTrace();
        }         
    }
    public void createTutorialPlot(Player player, int environmentRadius) {
        CompletableFuture.runAsync(() -> {
            create(player, environmentRadius, (plotRegion, environmentRegion, plotCenter) -> {
                try {
                    // Inform player about the plot creation
                    player.sendMessage(Utils.getInfoMessageFormat("Creating plot...", plugin.getConfig()));


                    // Convert polygon outline data to string
                    String polyOutline;
                    List<String> points = new ArrayList<>();

                    for (BlockVector2D point : plotRegion.getPoints())
                        points.add(point.getX() + "," + point.getZ());
                    polyOutline = StringUtils.join(points, "|");
                    Bukkit.getLogger().log(Level.INFO, "Tutorial plot outlines: " + polyOutline);

                    // Save plot and environment regions to schematic files
                    // Get plot schematic file path
                    String plotFilePath = createPlotSchematic(plotRegion, Paths.get(schematicsPath, "tutorials", "id-stage.schematic").toString());

                    if (plotFilePath == null) {
                        Bukkit.getLogger().log(Level.SEVERE, "Could not create plot schematic file!");
                        player.sendMessage(Utils.getErrorMessageFormat("An error occurred while creating plot!", plugin.getConfig()));
                        return;
                    }
                    Bukkit.getLogger().log(Level.INFO, "Tutorial plot schematic path: " + plotFilePath);

                    // Get environment schematic file path
                    if (environmentRadius > 0) {
                        String environmentFilePath = createPlotSchematic(environmentRegion, Paths.get(schematicsPath, "tutorials", "id-env.schematic").toString());

                        if (environmentFilePath == null) {
                            Bukkit.getLogger().log(Level.SEVERE, "Could not create environment schematic file!");
                            player.sendMessage(Utils.getErrorMessageFormat("An error occurred while creating plot!", plugin.getConfig()));
                            return;
                        }
                        Bukkit.getLogger().log(Level.INFO, "Tutorial environment schematic path: " + environmentFilePath);
                    }

                    player.sendMessage(Utils.getInfoMessageFormat("Successfully created new tutorial plot! Check your console for more information!", plugin.getConfig()));
                    player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);
                } catch (Exception ex) {
                    Bukkit.getLogger().log(Level.SEVERE, "An error occurred while creating plot!", ex);
                    player.sendMessage(Utils.getErrorMessageFormat("An error occurred while creating plot!", plugin.getConfig()));
                }
            });
        });
    }



    /**
     * Creates a plot schematic of a selected region from the player.
     *
     * @param region: Selected poly region of the player
     * @return the file path of the created schematic
     */
    private String createPlotSchematic(AbstractRegion region, String filePath) throws IOException, WorldEditException {
        // Create File
        File schematic = new File(filePath);

        // Delete file if exists
        Files.deleteIfExists(schematic.getAbsoluteFile().toPath());
        boolean createdDirs = schematic.getParentFile().mkdirs();
        boolean createdFile = schematic.createNewFile();

        if ((!schematic.getParentFile().exists() && !createdDirs) || (!schematic.exists() && !createdFile))
            return null;


        // Store content of region in schematic
        BlockArrayClipboard cb = new BlockArrayClipboard(region);
        cb.setOrigin(new Vector(region.getCenter().getX(), region.getMinimumPoint().getY(), region.getCenter().getZ()));


        EditSession editSession = WorldEdit.getInstance().getEditSessionFactory().getEditSession(region.getWorld(), -1);
        ForwardExtentCopy forwardExtentCopy = new ForwardExtentCopy(editSession, region, cb, region.getMinimumPoint());
        Operations.complete(forwardExtentCopy);

        try(ClipboardWriter writer = ClipboardFormat.SCHEMATIC.getWriter(new FileOutputStream(schematic, false))) {
            writer.write(cb, Objects.requireNonNull(region.getWorld()).getWorldData());
        }

        return filePath;
    }

    /**
     * Checks if polygon region contains a sign and update sign text
     * @param polyRegion WorldEdit region
     * @param world Region world
     * @return true if polygon region contains a sign, false otherwise
     */
    private static boolean containsSign(Polygonal2DRegion polyRegion, World world) {
        boolean hasSign = false;
        for (int i = polyRegion.getMinimumPoint().getBlockX(); i <= polyRegion.getMaximumPoint().getBlockX(); i++) {
            for (int j = polyRegion.getMinimumPoint().getBlockY(); j <= polyRegion.getMaximumPoint().getBlockY(); j++) {
                for (int k = polyRegion.getMinimumPoint().getBlockZ(); k <= polyRegion.getMaximumPoint().getBlockZ(); k++) {
                    if (polyRegion.contains(new Vector(i, j, k))) {
                        Block block = world.getBlockAt(i, j, k);
                        if(block.getType().equals(Material.SIGN_POST) || block.getType().equals(Material.WALL_SIGN)) {
                            hasSign = true;

                            Sign sign = (Sign) block.getState();
                            for (int s = 0; s < 4; s++) {
                                if(s == 1) {
                                    sign.setLine(s, "§c§lStreet Side");
                                } else {
                                    sign.setLine(s, "");
                                }
                            }
                            sign.update();
                        }
                    }
                }
            }
        }
        return hasSign;
    }

    /**
     * Places a plot marker in the center of the polygon region
     * @param plotRegion WorldEdit region
     * @param player Player
     * @param plotID Plot ID
     */
    private void placePlotMarker(Region plotRegion, Player player, int plotID) {
        Vector centerBlock = plotRegion.getCenter();
        Location highestBlock = player.getWorld().getHighestBlockAt(centerBlock.getBlockX(), centerBlock.getBlockZ()).getLocation();

        Bukkit.getScheduler().runTask(plugin, () -> {
            player.getWorld().getBlockAt(highestBlock).setType(Material.SEA_LANTERN);
            player.getWorld().getBlockAt(highestBlock.add(0, 1, 0)).setType(Material.SIGN_POST);
            Block signBlock = player.getWorld().getBlockAt(highestBlock);

            Sign sign = (Sign) signBlock.getState();
            org.bukkit.material.Sign matSign =  new org.bukkit.material.Sign(Material.SIGN_POST);
            matSign.setFacingDirection(getPlayerFaceDirection(player).getOppositeFace());
            sign.setData(matSign);
            sign.setLine(0, "§8§lID: §c§l" + plotID);
            sign.setLine(2, "§8§lCreated By:");
            sign.setLine(3, "§c§l" + player.getName());
            sign.update();
        });
    }

    /**
     * Gets the direction the player is facing
     * @param player Player
     * @return Direction
     */
    private static BlockFace getPlayerFaceDirection(Player player) {
        float y = player.getLocation().getYaw();
        if( y < 0 ){y += 360;}
        y %= 360;
        int i = (int)((y+8) / 22.5);
        return BlockFace.values()[i];
    }
}