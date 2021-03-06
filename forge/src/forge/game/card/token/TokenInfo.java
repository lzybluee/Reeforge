package forge.game.card.token;

import com.google.common.base.Joiner;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import forge.ImageKeys;
import forge.card.CardType;
import forge.card.MagicColor;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardFactory;
import forge.game.card.CardFactoryUtil;
import forge.game.keyword.KeywordInterface;
import forge.game.player.Player;

import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.tuple.Pair;

public class TokenInfo {
    final String name;
    final String imageName;
    final String manaCost;
    final String[] types;
    final String[] intrinsicKeywords;
    final int basePower;
    final int baseToughness;
    final String color;

    public TokenInfo(String name, String imageName, String manaCost, String[] types,
                     String[] intrinsicKeywords, int basePower, int baseToughness) {
        this.name = name;
        this.imageName = imageName;
        this.manaCost = manaCost;
        this.color = manaCost; // FIXME: somehow ensure that color and mana cost are completely differentiated
        this.types = types;
        this.intrinsicKeywords = intrinsicKeywords;
        this.basePower = basePower;
        this.baseToughness = baseToughness;
    }

    public TokenInfo(Card c) {
        this.name = c.getName();
        this.imageName = ImageKeys.getTokenImageName(c.getImageKey());
        this.manaCost = c.getManaCost().toString();
        this.color = MagicColor.toShortString(c.getCurrentState().getColor());
        this.types = getCardTypes(c);
        
        List<String> list = Lists.newArrayList();
        for (KeywordInterface inst : c.getKeywords()) {
            list.add(inst.getOriginal());
        }
        
        this.intrinsicKeywords   = list.toArray(new String[0]);
        this.basePower = c.getBasePower();
        this.baseToughness = c.getBaseToughness();
    }

    public TokenInfo(String str) {
        final String[] tokenInfo = str.split(",");
        int power = 0;
        int toughness = 0;
        String manaCost = "0";
        String[] types = null;
        String[] keywords = null;
        String imageName = null;
        String color = "";
        for (String info : tokenInfo) {
            int index = info.indexOf(':');
            if (index == -1) {
                continue;
            }
            String remainder = info.substring(index + 1);
            if (info.startsWith("P:")) {
                power = Integer.parseInt(remainder);
            } else if (info.startsWith("T:")) {
                toughness = Integer.parseInt(remainder);
            } else if (info.startsWith("Cost:")) {
                manaCost = remainder;
            } else if (info.startsWith("Types:")) {
                types = remainder.split("-");
            } else if (info.startsWith("Keywords:")) {
                keywords = remainder.split("-");
            } else if (info.startsWith("Image:")) {
                imageName = remainder;
            } else if (info.startsWith("Color:")) {
                color = remainder;
            }
        }

        this.name = tokenInfo[0];
        this.imageName = imageName;
        this.manaCost = manaCost;
        this.types = types;
        this.intrinsicKeywords = keywords;
        this.basePower = power;
        this.baseToughness = toughness;
        this.color = color;
    }

    private static String[] getCardTypes(Card c) {
        List<String> relevantTypes = Lists.newArrayList();
        for (CardType.CoreType t : c.getType().getCoreTypes()) {
            relevantTypes.add(t.name());
        }
        Iterables.addAll(relevantTypes, c.getType().getSubtypes());
        if (c.getType().isLegendary()) {
            relevantTypes.add("Legendary");
        }
        return relevantTypes.toArray(new String[relevantTypes.size()]);
    }

    private Card toCard(Game game) {
        final Card c = new Card(game.nextCardId(), game);
        c.setName(name);
        c.setImageKey(ImageKeys.getTokenKey(imageName));

        // TODO - most tokens mana cost is 0, this needs to be fixed
        // c.setManaCost(manaCost);
        c.setColor(color.isEmpty() ? manaCost : color);
        c.setToken(true);

        for (final String t : types) {
            c.addType(t);
        }

        c.setBasePower(basePower);
        c.setBaseToughness(baseToughness);
        return c;
    }

    public Card toTokenCard(Game game) {
        final Card c = new Card(0, game);
        c.setName(name);

        c.setColor(color.isEmpty() ? manaCost : color);
        c.setToken(true);

        for (final String t : types) {
            c.addType(t);
        }

        c.setBasePower(basePower);
        c.setBaseToughness(baseToughness);
        return c;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(name).append(',');
        sb.append("P:").append(basePower).append(',');
        sb.append("T:").append(baseToughness).append(',');
        sb.append("Cost:").append(manaCost).append(',');
        sb.append("Color:").append(color).append(",");
        sb.append("Types:").append(Joiner.on('-').join(types)).append(',');
        sb.append("Keywords:").append(Joiner.on('-').join(intrinsicKeywords)).append(',');
        sb.append("Image:").append(imageName);
        return sb.toString();
    }

    public static Pair<Player, Integer> calculateMultiplier(final Game game, final Player controller,
            final boolean applyMultiplier, final int num) {
    	return calculateMultiplier(game, controller, applyMultiplier, num, null);
    }
            
    public static Pair<Player, Integer> calculateMultiplier(final Game game, final Player controller,
            final boolean applyMultiplier, final int num, final Card tokenCard) {
        int multiplier = num;
        Player player = controller;

        final Map<String, Object> repParams = Maps.newHashMap();
        repParams.put("Event", "CreateToken");
        repParams.put("Affected", player);
        repParams.put("TokenNum", multiplier);
        repParams.put("EffectOnly", applyMultiplier);
        repParams.put("Token", tokenCard);

        switch (game.getReplacementHandler().run(repParams)) {
            case NotReplaced:
                break;
            case Updated: {
                multiplier = (int) repParams.get("TokenNum");
                player = (Player) repParams.get("Affected");
                break;
            }
            default:
                multiplier = 0;
                break;
        }
        return Pair.of(player, multiplier);
    }

    public List<Card> makeTokenWithMultiplier(final Player controller, int amount, final boolean applyMultiplier) {
        final List<Card> list = Lists.newArrayList();
        final Game game = controller.getGame();

        Pair<Player, Integer> result = calculateMultiplier(game, controller, applyMultiplier, amount, this.toTokenCard(game));

        for (int i = 0; i < result.getRight(); i++) {
            list.add(makeOneToken(result.getLeft()));
        }
        return list;
    }

    static public List<Card> makeTokensFromPrototype(Card prototype, final Player controller, int amount, final boolean applyMultiplier) {
        final List<Card> list = Lists.newArrayList();
        final Game game = controller.getGame();

        Pair<Player, Integer> result = calculateMultiplier(game, controller, applyMultiplier, amount);
        long timestamp = game.getNextTimestamp();
        prototype.setController(result.getLeft(), timestamp);
        for (int i = 0; i < result.getRight(); i++) {
            Card copy = CardFactory.copyCard(prototype, true);
            copy.setTimestamp(timestamp);
            copy.setOwner(result.getLeft());
            copy.setToken(true);
            list.add(copy);
        }

        return list;
    }

    public Card makeOneToken(final Player controller) {
        final Game game = controller.getGame();
        final Card c = toCard(game);

        c.setOwner(controller);
        c.setToken(true);
        CardFactoryUtil.setupKeywordedAbilities(c);
        // add them later to prevent setupKeywords from adding them multiple times
        for (final String kw : intrinsicKeywords) {
            c.addIntrinsicKeyword(kw);
        }
        return c;
    }
}