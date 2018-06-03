/*
 * Forge: Play Magic: the Gathering.
 * Copyright (C) 2011  Forge Team
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package forge.util;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.security.SecureRandom;
import java.util.Date;
import java.util.List;
import java.util.Random;
import java.util.Vector;

import forge.game.player.RegisteredPlayer;

/**
 * <p>
 * MyRandom class.<br>
 * Preferably all Random numbers should be retrieved using this wrapper class
 * </p>
 * 
 * @author Forge
 * @version $Id$
 */
public class MyRandom {
    /** Constant <code>random</code>. */
    private static Random random = new SecureRandom();

    private static byte[] currentSeed;
    private static String matchDesc = "";
    private static Vector<String> loadedSeeds = null;

    public static void saveSeed(String startPlayer) {
        String seed = "";
        for(byte b : currentSeed) {
            seed += String.format("%02x", b);
        }
        try {
            FileOutputStream stream = new FileOutputStream("save.txt", true);
            OutputStreamWriter writer = new OutputStreamWriter(stream);
            BufferedWriter buf = new BufferedWriter(writer);
            buf.write(new Date().toString());
            buf.newLine();
            buf.write(matchDesc);
            buf.newLine();
            buf.write(startPlayer);
            buf.newLine();
            buf.write(seed);
            buf.newLine();
            buf.newLine();
            buf.close();
            writer.close();
            stream.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static byte[] loadSeed() {
        if(!new File("load.txt").exists()) {
            return null;
        }
        if(loadedSeeds == null) {
            loadedSeeds = new Vector<>();
            try {
                FileInputStream stream = new FileInputStream("load.txt");
                InputStreamReader reader = new InputStreamReader(stream);
                BufferedReader buf = new BufferedReader(reader);
                String line = null;
                while((line = buf.readLine()) != null) {
                    if(line.length() == 32 && !line.contains(":") && !line.contains("|") && !line.contains("->")) {
                        loadedSeeds.add(line);
                    }
                }
                buf.close();
                reader.close();
                stream.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        if(!loadedSeeds.isEmpty()) {
            String seed = loadedSeeds.get(0);
            loadedSeeds.remove(0);
            byte[] bytes = new byte[16];
            for(int i = 0; i < 16; i++) {
                bytes[i] = (byte)Integer.parseInt(seed.substring(i * 2, i * 2 + 2), 16);
            }
            return bytes;
        }
        return null;
    }

    public static void updateSeed(List<RegisteredPlayer> players, int match) {
        byte[] loadSeed = loadSeed();
        if(loadSeed == null) {
            currentSeed = SecureRandom.getSeed(16);
            matchDesc = "";
        } else {
            currentSeed = loadSeed;
            matchDesc = "Loaded seed -> ";
        }
        matchDesc += players.get(0).getDeck().getName() + "|" + players.get(1).getDeck().getName() + "|" + match;
        random = new SecureRandom(currentSeed);
    }

    /**
     * <p>
     * percentTrue.<br>
     * If percent is like 30, then 30% of the time it will be true.
     * </p>
     * 
     * @param percent
     *            a int.
     * @return a boolean.
     */
    public static boolean percentTrue(final int percent) {
        return percent > MyRandom.getRandom().nextInt(100);
    }

    /**
     * Gets the random.
     * 
     * @return the random
     */
    public static Random getRandom() {
        return MyRandom.random;
    }

    public static int[] splitIntoRandomGroups(final int value, final int numGroups) {
        int[] groups = new int[numGroups];
        
        for (int i = 0; i < value; i++) {
            groups[random.nextInt(numGroups)]++;
        }

        return groups;
    }
}
