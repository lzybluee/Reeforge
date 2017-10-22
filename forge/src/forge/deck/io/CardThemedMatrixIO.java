package forge.deck.io;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import forge.game.GameFormat;
import forge.item.PaperCard;
import forge.properties.ForgeConstants;

/**
 * Created by maustin on 11/05/2017.
 */
public class CardThemedMatrixIO {

    /** suffix for all gauntlet data files */
    public static final String SUFFIX_DATA = ".dat";

    public static void saveMatrix(GameFormat format, HashMap<String,List<Map.Entry<PaperCard,Integer>>> map){
        File file = getMatrixFile(format);
        ObjectOutputStream s = null;
        try {
            FileOutputStream f = new FileOutputStream(file);
            s = new ObjectOutputStream(f);
            s.writeObject(map);
            s.close();
        } catch (IOException e) {
            System.out.println("Error writing matrix data: " + e);
        } finally {
            if(s!=null) {
                try {
                    s.close();
                }catch(Exception e){
                    e.printStackTrace();
                }
            }
        }
    }

    public static HashMap<String,List<Map.Entry<PaperCard,Integer>>> loadMatrix(GameFormat format){
        try {
            FileInputStream fin = new FileInputStream(getMatrixFile(format));
            ObjectInputStream s = new ObjectInputStream(fin);
            @SuppressWarnings("unchecked")
            HashMap<String, List<Map.Entry<PaperCard,Integer>>> matrix = (HashMap<String, List<Map.Entry<PaperCard,Integer>>>) s.readObject();
            s.close();
            return matrix;
        }catch (Exception e){
            System.out.println("Error reading matrix data: " + e);
            return null;
        }

    }

    public static File getMatrixFile(final String name) {
        return new File(ForgeConstants.DECK_GEN_DIR, name + SUFFIX_DATA);
    }

    public static File getMatrixFile(final GameFormat gf) {
        return getMatrixFile(gf.getName());
    }
}
