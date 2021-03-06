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
package forge.game.trigger;

import forge.game.card.Card;
import forge.game.card.CardUtil;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.util.Expressions;

/**
 * <p>
 * Trigger_DamageDone class.
 * </p>
 *
 * @author Forge
 * @version $Id$
 */
public class TriggerDamageDone extends Trigger {

    /**
     * <p>
     * Constructor for Trigger_DamageDone.
     * </p>
     *
     * @param params
     *            a {@link java.util.HashMap} object.
     * @param host
     *            a {@link forge.game.card.Card} object.
     * @param intrinsic
     *            the intrinsic
     */
    public TriggerDamageDone(final java.util.Map<String, String> params, final Card host, final boolean intrinsic) {
        super(params, host, intrinsic);
    }

    /** {@inheritDoc} */
    @Override
    public final boolean performTest(final java.util.Map<String, Object> runParams2) {
        final Card src = (Card) runParams2.get("DamageSource");
        final Object tgt = runParams2.get("DamageTarget");

        if (this.mapParams.containsKey("ValidSource")) {
            if (!src.isValid(this.mapParams.get("ValidSource").split(","), this.getHostCard().getController(),
                    this.getHostCard(), null)) {
                return false;
            }
        }

        if (this.mapParams.containsKey("ValidTarget")) {
            if (!matchesValid(tgt, this.mapParams.get("ValidTarget").split(","), this.getHostCard())) {
                return false;
            }
        }

        if (this.mapParams.containsKey("CombatDamage")) {
            if (this.mapParams.get("CombatDamage").equals("True")) {
                if (!((Boolean) runParams2.get("IsCombatDamage"))) {
                    return false;
                }
            } else if (this.mapParams.get("CombatDamage").equals("False")) {
                if (((Boolean) runParams2.get("IsCombatDamage"))) {
                    return false;
                }
            }
        }

        if (this.mapParams.containsKey("DamageAmount")) {
            final String fullParam = this.mapParams.get("DamageAmount");

            final String operator = fullParam.substring(0, 2);
            final int operand = Integer.parseInt(fullParam.substring(2));
            final int actualAmount = (Integer) runParams2.get("DamageAmount");

            if (!Expressions.compare(actualAmount, operator, operand)) {
                return false;
            }

            System.out.print("DamageDone Amount Operator: ");
            System.out.println(operator);
            System.out.print("DamageDone Amount Operand: ");
            System.out.println(operand);
        }

        if (this.mapParams.containsKey("OncePerEffect")) {
            // A "once per effect" trigger will only trigger once regardless of how many things the effect caused
            // to change zones.

            // The SpellAbilityStackInstance keeps track of which host cards with "OncePerEffect"
            // triggers already fired as a result of that effect.
            // TODO This isn't quite ideal, since it really should be keeping track of the SpellAbility of the host
            // card, rather than keeping track of the host card itself - but it's good enough for now - since there
            // are no cards with multiple different OncePerEffect triggers.
            SpellAbilityStackInstance si = (SpellAbilityStackInstance) runParams2.get("SpellAbilityStackInstance");

            // si == null means the stack is empty
            return si == null || !si.hasOncePerEffectTrigger(this);
        }

        return true;
    }

    /** {@inheritDoc} */
    @Override
    public final void setTriggeringObjects(final SpellAbility sa) {
        sa.setTriggeringObject("Source", (Card)this.getRunParams().get("DamageSource"));
        sa.setTriggeringObject("Target", this.getRunParams().get("DamageTarget"));
        sa.setTriggeringObject("DamageAmount", this.getRunParams().get("DamageAmount"));
        // This parameter is here because LKI information related to combat doesn't work properly
        sa.setTriggeringObject("DefendingPlayer", this.getRunParams().get("DefendingPlayer"));
    }

    @Override
    public String getImportantStackObjects(SpellAbility sa) {
        StringBuilder sb = new StringBuilder();
        sb.append("Damage Source: ").append(sa.getTriggeringObject("Source")).append(", ");
        sb.append("Damaged: ").append(sa.getTriggeringObject("Target")).append(", ");
        sb.append("Amount: ").append(sa.getTriggeringObject("DamageAmount"));
        return sb.toString();
    }
}

