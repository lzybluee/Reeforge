package forge.game.ability.effects;

import forge.card.ColorSet;
import forge.card.MagicColor;
import forge.card.mana.ManaAtom;
import forge.card.mana.ManaCostShard;
import forge.game.Game;
import forge.game.GameActionUtil;
import forge.game.ability.AbilityUtils;
import forge.game.ability.SpellAbilityEffect;
import forge.game.card.Card;
import forge.game.mana.Mana;
import forge.game.player.Player;
import forge.game.spellability.AbilityManaPart;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.TargetRestrictions;
import org.apache.commons.lang3.StringUtils;

import com.google.common.collect.Iterables;

import java.util.ArrayList;
import java.util.List;

public class ManaEffect extends SpellAbilityEffect {

    @Override
    public void resolve(SpellAbility sa) {
        final Card card = sa.getHostCard();

        AbilityManaPart abMana = sa.getManaPart();

        // Spells are not undoable
        sa.setUndoable(sa.isAbility() && sa.isUndoable());

        final List<Player> tgtPlayers = getTargetPlayers(sa);
        final TargetRestrictions tgt = sa.getTargetRestrictions();
        final boolean optional = sa.hasParam("Optional");
        final Game game = sa.getActivatingPlayer().getGame();

        if (optional && !sa.getActivatingPlayer().getController().confirmAction(sa, null, "Do you want to add mana?")) {
            return;
        }

        if (sa.hasParam("DoubleManaInPool")) {
            for (final Player player : tgtPlayers) {
                for (byte color : ManaAtom.MANATYPES) {
                    int amountColor = player.getManaPool().getAmountOfColor(color);
                    for (int i = 0; i < amountColor; i++) {
                        abMana.produceMana(MagicColor.toShortString(color), player, sa);
                    }
                }
            }
        }

        if (sa.hasParam("ProduceNoOtherMana")) {
            return;
        }

        if (abMana.isComboMana()) {
            for (Player p : tgtPlayers) {
                int amount = sa.hasParam("Amount") ? AbilityUtils.calculateAmount(card, sa.getParam("Amount"), sa) : 1;
                if (tgt == null || p.canBeTargetedBy(sa)) {
                    Player activator = sa.getActivatingPlayer();
                    String express = abMana.getExpressChoice();
                    String colorsProducedStr = abMana.getComboColors();
                    String[] colorsProduced = colorsProducedStr.split(" ");

                    final StringBuilder choiceString = new StringBuilder();
                    ColorSet colorOptions = null;
                    String[] colorsNeeded = express.isEmpty() ? null : express.split(" ");
                    if (!abMana.isAnyMana()) {
                        colorOptions = ColorSet.fromNames(colorsProduced);
                    } else {
                        colorOptions = ColorSet.fromNames(MagicColor.Constant.ONLY_COLORS);
                    }
                    final ColorSet fullOptions = colorOptions;
                    for (int nMana = 1; nMana <= amount; nMana++) {
                        String choice = "";
                        if (colorsNeeded != null && colorsNeeded.length >= nMana) {	// select from express choices if possible
                            colorOptions = ColorSet
                                    .fromMask(fullOptions.getColor() & ManaAtom.fromName(colorsNeeded[nMana - 1]));
                        }
                        if (colorsProduced.length > 0 && !colorsProduced[0].isEmpty() && sa.getNeedChooseMana()) {
                            choice = MagicColor.toShortString(activator.getController().chooseColor("Select Mana to Produce", sa, fullOptions));
                        } else if (colorOptions.isColorless() && colorsProduced.length > 0) {
                            // If we just need generic mana, no reason to ask the controller for a choice,
                            // just use the first possible color.
                            choice = colorsProduced[0];
                        } else {
                            byte chosenColor = 0;

                            if(sa.getUsedToPayMana() != null) {
                                String usedToPayMana = sa.getUsedToPayMana().toString();
                                ArrayList<String> new_colors = new ArrayList<>();
                                
                                if((colorOptions.hasWhite() || colorsProducedStr.contains("W")) && (usedToPayMana.contains("{W") || usedToPayMana.contains("W}"))) {
                                    new_colors.add("white");
                                }
                                if((colorOptions.hasBlue() || colorsProducedStr.contains("U")) && (usedToPayMana.contains("{U") || usedToPayMana.contains("U}"))) {
                                    new_colors.add("blue");
                                }
                                if((colorOptions.hasBlack() || colorsProducedStr.contains("B")) && (usedToPayMana.contains("{B") || usedToPayMana.contains("B}"))) {
                                    new_colors.add("black");
                                }
                                if((colorOptions.hasRed() || colorsProducedStr.contains("R")) && (usedToPayMana.contains("{R") || usedToPayMana.contains("R}"))) {
                                    new_colors.add("red");
                                }
                                if((colorOptions.hasGreen() || colorsProducedStr.contains("G")) && (usedToPayMana.contains("{G") || usedToPayMana.contains("G}"))) {
                                    new_colors.add("green");
                                }
                                
                                if(new_colors.size() == 1) {
                                    chosenColor = MagicColor.fromName(new_colors.iterator().next());
                                } else if(new_colors.size() > 1) {
                                    chosenColor = activator.getController().chooseColor("Select Mana to Produce", sa, ColorSet.fromNames(new_colors));
                                } else {
                                    chosenColor = MagicColor.fromName(MagicColor.toLongString(colorOptions.iterator().next()));
                                }
                            }
                            else {
                                chosenColor = activator.getController().chooseColor("Select Mana to Produce", sa, colorOptions);
                            }

                            if (chosenColor == 0)
                                throw new RuntimeException("ManaEffect::resolve() /*combo mana*/ - " + activator + " color mana choice is empty for " + card.getName());
                            choice = MagicColor.toShortString(chosenColor);
                        }
                        
                        if (nMana != 1) {
                            choiceString.append(" ");
                        }
                        choiceString.append(choice);
                    }

                    if (choiceString.toString().isEmpty() && "Combo ColorIdentity".equals(abMana.getOrigProduced())) {
                        // No mana could be produced here (non-EDH match?), so cut short
                        return;
                    }

                    game.action.nofityOfValue(sa, card, activator + " picked " + choiceString, activator);
                    abMana.setExpressChoice(choiceString.toString());
                }
            }
        }
        else if (abMana.isAnyMana()) {
        	if(sa.hasParam("Amount") && sa.getParam("Amount").equals("X")) {
        		int n = AbilityUtils.calculateAmount(card, "X", sa);
        		if(n == 0) {
        			return;
        		}
        	}
            for (Player p : tgtPlayers) {
                if (tgt == null || p.canBeTargetedBy(sa)) {
                    Player act = sa.getActivatingPlayer();
                    // AI color choice is set in ComputerUtils so only human players need to make a choice
    
                    String colorsNeeded = abMana.getExpressChoice();
                    String choice = "";

                    ColorSet colorMenu = null;
                    byte mask = 0;
                    //loop through colors to make menu
                    for (int nChar = 0; nChar < colorsNeeded.length(); nChar++) {
                        mask |= MagicColor.fromName(colorsNeeded.charAt(nChar));
                    }
                    colorMenu = mask == 0 ? ColorSet.ALL_COLORS : ColorSet.fromMask(mask);
                    byte val = p.getController().chooseColor("Select Mana to Produce", sa, colorMenu);
                    if (0 == val) {
                        throw new RuntimeException("ManaEffect::resolve() /*any mana*/ - " + act + " color mana choice is empty for " + card.getName());
                    }
                    choice = MagicColor.toShortString(val);

                    game.action.nofityOfValue(sa, card, act + " picked " + choice, act);
                    abMana.setExpressChoice(choice);
                }
            }
        }
        else if (abMana.isSpecialMana()) {
            for (Player p : tgtPlayers) {
                if (tgt == null || p.canBeTargetedBy(sa)) {
                    String type = abMana.getOrigProduced().split("Special ")[1];

                    if (type.equals("EnchantedManaCost")) {
                        Card enchanted = card.getEnchantingCard();
                        if (enchanted == null ) 
                            continue;

                        StringBuilder sb = new StringBuilder();
                        int generic = enchanted.getManaCost().getGenericCost();
                        if( generic > 0 )
                            sb.append(generic);

                        for (ManaCostShard s : enchanted.getManaCost()) {
                            ColorSet cs = ColorSet.fromMask(s.getColorMask());
                            if(cs.isColorless())
                                continue;
                            sb.append(' ');
                            if (cs.isMonoColor())
                                sb.append(MagicColor.toShortString(s.getColorMask()));
                            else /* (cs.isMulticolor()) */ {
                                byte chosenColor = sa.getActivatingPlayer().getController().chooseColor("Choose a single color from " + s.toString(), sa, cs);
                                sb.append(MagicColor.toShortString(chosenColor));
                            }
                        }
                        abMana.setExpressChoice(sb.toString().trim());
                    } else if (type.equals("LastNotedType")) {
                        Mana manaType = (Mana) Iterables.getFirst(card.getRemembered(), null);
                        if (manaType == null) {
                            return;
                        }
                        String  cs = manaType.toString();
                        abMana.setExpressChoice(cs);
                    }

                    if (abMana.getExpressChoice().isEmpty()) {
                        System.out.println("AbilityFactoryMana::manaResolve() - special mana effect is empty for " + sa.getHostCard().getName());
                    }
                }
            }    
        }

        for (final Player player : tgtPlayers) {
            abMana.produceMana(GameActionUtil.generatedMana(sa), player, sa);
        }

        // Only clear express choice after mana has been produced
        abMana.clearExpressChoice();

        //resolveDrawback(sa);
    }

    /**
     * <p>
     * manaStackDescription.
     * </p>
     * @param sa
     *            a {@link forge.game.spellability.SpellAbility} object.
     * @param abMana
     *            a {@link forge.card.spellability.AbilityMana} object.
     * @param af
     *            a {@link forge.game.ability.AbilityFactory} object.
     * 
     * @return a {@link java.lang.String} object.
     */

    @Override
    protected String getStackDescription(SpellAbility sa) {
        final StringBuilder sb = new StringBuilder();
        String mana = !sa.hasParam("Amount") || StringUtils.isNumeric(sa.getParam("Amount"))
                ? GameActionUtil.generatedMana(sa) : "mana";
        sb.append("Add ").append(mana).append(".");
        return sb.toString();
    }
}
