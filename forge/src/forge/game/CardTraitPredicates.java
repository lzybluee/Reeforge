package forge.game;

import com.google.common.base.Predicate;

public class CardTraitPredicates {

    public static final Predicate<CardTraitBase> hasParam(final String name) {
        return new Predicate<CardTraitBase>() {
            @Override
            public boolean apply(final CardTraitBase sa) {
                return sa.hasParam(name);
            }
        };
    }

    public static final Predicate<CardTraitBase> hasParam(final String name, final String val) {
        return new Predicate<CardTraitBase>() {
            @Override
            public boolean apply(final CardTraitBase sa) {
                if (!sa.hasParam(name)) {
                    return false;
                }
                return val.equals(sa.getParam(name));
            }
        };
    }
}
