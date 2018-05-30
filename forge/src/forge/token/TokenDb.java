package forge.token;

import com.google.common.base.Predicate;
import com.google.common.collect.Maps;
import forge.card.*;
import forge.item.PaperToken;

import java.util.*;

public class TokenDb implements ITokenDatabase {
    // Expected naming convention of scripts
    // token_name
    // minor_demon
    // marit_lage
    // gold

    // colors_power_toughness_cardtypes_sub_types_keywords
    // Some examples:
    // c_3_3_a_wurm_lifelink
    // w_2_2_knight_first_strike

    // The image names should be the same as the script name + _set
    // If that isn't found, consider falling back to the original token

    private final Map<String, PaperToken> tokensByName = Maps.newTreeMap(String.CASE_INSENSITIVE_ORDER);

    private final CardEdition.Collection editions;
    private final Map<String, CardRules> rulesByName;

    public TokenDb(Map<String, CardRules> rules, CardEdition.Collection editions) {
        this.rulesByName = rules;
        this.editions = editions;
    }

    @Override
    public PaperToken getToken(String tokenName) {
        return getToken(tokenName, CardEdition.UNKNOWN.getName());
    }

    @Override
    public PaperToken getToken(String tokenName, String edition) {
        try {
            PaperToken pt = new PaperToken(rulesByName.get(tokenName), editions.get(edition));
            // TODO Cache the token after it's referenced
            return pt;
        } catch(Exception e) {
            return null;
        }
    }

    @Override
    public PaperToken getToken(String tokenName, String edition, int artIndex) {
        return null;
    }

    @Override
    public PaperToken getTokenFromEdition(String tokenName, CardDb.SetPreference fromSet) {
        return null;
    }

    @Override
    public PaperToken getTokenFromEdition(String tokenName, Date printedBefore, CardDb.SetPreference fromSet) {
        return null;
    }

    @Override
    public PaperToken getTokenFromEdition(String tokenName, Date printedBefore, CardDb.SetPreference fromSet, int artIndex) {
        return null;
    }

    @Override
    public PaperToken getFoiled(PaperToken cpi) {
        return null;
    }

    @Override
    public int getPrintCount(String cardName, String edition) {
        return 0;
    }

    @Override
    public int getMaxPrintCount(String cardName) {
        return 0;
    }

    @Override
    public int getArtCount(String cardName, String edition) {
        return 0;
    }

    @Override
    public Collection<PaperToken> getUniqueTokens() {
        return null;
    }

    @Override
    public List<PaperToken> getAllTokens() {
        return null;
    }

    @Override
    public List<PaperToken> getAllTokens(String tokenName) {
        return null;
    }

    @Override
    public List<PaperToken> getAllTokens(Predicate<PaperToken> predicate) {
        return null;
    }

    @Override
    public Predicate<? super PaperToken> wasPrintedInSets(List<String> allowedSetCodes) {
        return null;
    }

    @Override
    public Iterator<PaperToken> iterator() {
        return null;
    }
}
