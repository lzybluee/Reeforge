package forge.itemmanager;

import forge.game.GameFormat;
import forge.gui.GuiUtils;
import forge.item.PaperCard;
import forge.itemmanager.filters.*;
import forge.model.FModel;
import forge.quest.QuestWorld;
import forge.quest.data.QuestPreferences;
import forge.screens.home.quest.DialogChooseFormats;
import forge.screens.home.quest.DialogChooseSets;
import forge.screens.match.controllers.CDetailPicture;

import javax.swing.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map.Entry;

/** 
 * ItemManager for cards
 *
 */
@SuppressWarnings("serial")
public class CardManager extends ItemManager<PaperCard> {
    
    private boolean QuestMode;

    public CardManager(final CDetailPicture cDetailPicture, final boolean wantUnique0, final boolean qm) {
        super(PaperCard.class, cDetailPicture, wantUnique0);
        QuestMode = qm;
    }

    @Override
    protected void addDefaultFilters() {
        addDefaultFilters(this);
    }

    @Override
    protected ItemFilter<PaperCard> createSearchFilter() {
        return createSearchFilter(this);
    }

    @Override
    protected void buildAddFilterMenu(JMenu menu) {
        buildAddFilterMenu(menu, this);
    }

    @Override
    protected Iterable<Entry<PaperCard, Integer>> getUnique(Iterable<Entry<PaperCard, Integer>> items) {
        //use special technique for getting unique cards so that cards without art aren't shown
        HashMap<String, Entry<PaperCard, Integer>> map = new HashMap<String, Entry<PaperCard, Integer>>();
        for (Entry<PaperCard, Integer> item : items) {
            final String key = item.getKey().getName();
            final Entry<PaperCard, Integer> oldValue = map.get(key);
            if (oldValue == null || !oldValue.getKey().hasImage()) { //only replace in map if old value doesn't have image
                map.put(key, item);
            }
        }
        return map.values();
    }

    /* Static overrides shared with SpellShopManager*/

    public static void addDefaultFilters(final ItemManager<? super PaperCard> itemManager) {
        itemManager.addFilter(new CardColorFilter(itemManager));
        itemManager.addFilter(new CardTypeFilter(itemManager));
        itemManager.addFilter(new CardCMCFilter(itemManager));
        if (FModel.getQuestPreferences()
                .getPrefInt(QuestPreferences.QPref.FOIL_FILTER_DEFAULT) == 1) {
            itemManager.addFilter(new CardFoilFilter(itemManager));
        }
        if (FModel.getQuestPreferences()
                .getPrefInt(QuestPreferences.QPref.RATING_FILTER_DEFAULT) == 1) {
            itemManager.addFilter(new CardRatingFilter(itemManager));
        }
    }

    public static ItemFilter<PaperCard> createSearchFilter(final ItemManager<? super PaperCard> itemManager) {
        return new CardSearchFilter(itemManager);
    }

    public void buildAddFilterMenu(JMenu menu, final ItemManager<? super PaperCard> itemManager) {
        GuiUtils.addSeparator(menu); //separate from current search item

        JMenu fmt = GuiUtils.createMenu("Format");
        for (final GameFormat f : FModel.getFormats().getFilterList()) {
            GuiUtils.addMenuItem(fmt, f.getName(), null, new Runnable() {
                @Override
                public void run() {
                    itemManager.addFilter(new CardFormatFilter(itemManager, f));
                }
            }, FormatFilter.canAddFormat(f, itemManager.getFilter(CardFormatFilter.class)));
        }
        menu.add(fmt);

        GuiUtils.addMenuItem(menu, "Formats...", null, new Runnable() {
            @Override public void run() {
                final CardSetFilter existingFilter = itemManager.getFilter(CardSetFilter.class);
                if (existingFilter != null) {
                    existingFilter.edit();
                } else {
                    final DialogChooseFormats dialog = new DialogChooseFormats();
                    dialog.setOkCallback(new Runnable() {
                        @Override public void run() {
                            final List<GameFormat> formats = dialog.getSelectedFormats();
                            if (!formats.isEmpty()) {
                                for(GameFormat format: formats) {
                                    itemManager.addFilter(new CardFormatFilter(itemManager, format));
                                }
                            }
                        }
                    });
                }
            }
        });

        GuiUtils.addMenuItem(menu, "Sets...", null, new Runnable() {
            @Override
            public void run() {
                CardSetFilter existingFilter = itemManager.getFilter(CardSetFilter.class);
                if (existingFilter != null) {
                    existingFilter.edit();
                }
                else {
                    final DialogChooseSets dialog = new DialogChooseSets(null, null, true);
                    dialog.setOkCallback(new Runnable() {
                        @Override
                        public void run() {
                            List<String> sets = dialog.getSelectedSets();
                            if (!sets.isEmpty()) {
                                itemManager.addFilter(new CardSetFilter(itemManager, sets, dialog.getWantReprints()));
                            }
                        }
                    });
                }
            }
        });

        JMenu world = GuiUtils.createMenu("Quest world");
        for (final QuestWorld w : FModel.getWorlds()) {
            GuiUtils.addMenuItem(world, w.getName(), null, new Runnable() {
                @Override
                public void run() {
                    itemManager.addFilter(new CardQuestWorldFilter(itemManager, w));
                }
            }, CardQuestWorldFilter.canAddQuestWorld(w, itemManager.getFilter(CardQuestWorldFilter.class)));
        }
        menu.add(world);

        GuiUtils.addSeparator(menu);

        GuiUtils.addMenuItem(menu, "Colors", null, new Runnable() {
            @Override
            public void run() {
                itemManager.addFilter(new CardColorFilter(itemManager));
            }
        }, itemManager.getFilter(CardColorFilter.class) == null);
        GuiUtils.addMenuItem(menu, "Types", null, new Runnable() {
            @Override
            public void run() {
                itemManager.addFilter(new CardTypeFilter(itemManager));
            }
        }, itemManager.getFilter(CardTypeFilter.class) == null);
        GuiUtils.addMenuItem(menu, "Converted mana costs", null, new Runnable() {
            @Override
            public void run() {
                itemManager.addFilter(new CardCMCFilter(itemManager));
            }
        }, itemManager.getFilter(CardCMCFilter.class) == null);

        GuiUtils.addSeparator(menu);

        GuiUtils.addMenuItem(menu, "CMC range", null, new Runnable() {
            @Override
            public void run() {
                itemManager.addFilter(new CardCMCRangeFilter(itemManager));
            }
        }, itemManager.getFilter(CardCMCRangeFilter.class) == null);
        GuiUtils.addMenuItem(menu, "Power range", null, new Runnable() {
            @Override
            public void run() {
                itemManager.addFilter(new CardPowerFilter(itemManager));
            }
        }, itemManager.getFilter(CardPowerFilter.class) == null);
        GuiUtils.addMenuItem(menu, "Toughness range", null, new Runnable() {
            @Override
            public void run() {
                itemManager.addFilter(new CardToughnessFilter(itemManager));
            }
        }, itemManager.getFilter(CardToughnessFilter.class) == null);

        GuiUtils.addSeparator(menu);

        GuiUtils.addMenuItem(menu, "Foil", null, new Runnable() {
            @Override
            public void run() {
                itemManager.addFilter(new CardFoilFilter(itemManager));
            }
        }, itemManager.getFilter(CardFoilFilter.class) == null);

        if (QuestMode) {
            GuiUtils.addMenuItem(menu, "Personal Rating", null, new Runnable() {
                @Override
                public void run() {
                    itemManager.addFilter(new CardRatingFilter(itemManager));
                }
            }, itemManager.getFilter(CardRatingFilter.class) == null);
        }

        GuiUtils.addSeparator(menu);

        GuiUtils.addMenuItem(menu, "Advanced...", null, new Runnable() {
            @Override
            @SuppressWarnings("unchecked")
            public void run() {
                AdvancedSearchFilter<PaperCard> filter = itemManager.getFilter(AdvancedSearchFilter.class);
                if (filter != null) {
                    filter.edit();
                }
                else {
                    filter = new AdvancedSearchFilter<PaperCard>(itemManager);
                    itemManager.lockFiltering = true; //ensure filter not applied until added
                    boolean result = filter.edit();
                    itemManager.lockFiltering = false;
                    if (result) {
                        itemManager.addFilter(filter);
                    }
                }
            }
        });
    }
}
