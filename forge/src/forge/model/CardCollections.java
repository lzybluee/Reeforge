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
package forge.model;

import java.io.File;

import forge.deck.Deck;
import forge.deck.DeckGroup;
import forge.deck.io.DeckGroupSerializer;
import forge.deck.io.DeckStorage;
import forge.properties.ForgeConstants;
import forge.util.storage.IStorage;
import forge.util.storage.StorageImmediatelySerialized;

/**
 * Holds editable maps of decks saved to disk. Adding or removing items to(from)
 * such map turns into immediate file update
 */
public class CardCollections {
    // Note: These are loaded lazily.
    private IStorage<Deck> constructed;
    private IStorage<DeckGroup> draft;
    private IStorage<DeckGroup> sealed;
    private IStorage<DeckGroup> winston;
    private IStorage<Deck> cube;
    private IStorage<Deck> scheme;
    private IStorage<Deck> plane;
    private IStorage<Deck> commander;
    private IStorage<Deck> tinyLeaders;

    public CardCollections() {
    }

    public final IStorage<Deck> getConstructed() {
        if (constructed == null) {
            constructed = new StorageImmediatelySerialized<Deck>("Constructed decks",
                    new DeckStorage(new File(ForgeConstants.DECK_CONSTRUCTED_DIR), ForgeConstants.DECK_BASE_DIR, true),
                    true);
        }
        return constructed;
    }

    public final IStorage<DeckGroup> getDraft() {
        if (draft == null) {
            draft = new StorageImmediatelySerialized<DeckGroup>("Draft deck sets",
                    new DeckGroupSerializer(new File(ForgeConstants.DECK_DRAFT_DIR), ForgeConstants.DECK_BASE_DIR));
        }
        return draft;
    }

    public IStorage<DeckGroup> getSealed() {
        if (sealed == null) {
            sealed = new StorageImmediatelySerialized<DeckGroup>("Sealed deck sets",
                    new DeckGroupSerializer(new File(ForgeConstants.DECK_SEALED_DIR), ForgeConstants.DECK_BASE_DIR));
        }
        return sealed;
    }

    public final IStorage<DeckGroup> getWinston() {
        if (winston == null) {
            winston = new StorageImmediatelySerialized<DeckGroup>("Winston draft deck sets",
                    new DeckGroupSerializer(new File(ForgeConstants.DECK_WINSTON_DIR), ForgeConstants.DECK_BASE_DIR));
        }
        return winston;
    }

    public final IStorage<Deck> getCubes() {
        if (cube == null) {
            cube = new StorageImmediatelySerialized<Deck>("Cubes",
                    new DeckStorage(new File(ForgeConstants.DECK_CUBE_DIR), ForgeConstants.RES_DIR));
        }
        return cube;
    }

    public IStorage<Deck> getScheme() {
        if (scheme == null) {
            scheme = new StorageImmediatelySerialized<Deck>("Archenemy decks",
                    new DeckStorage(new File(ForgeConstants.DECK_SCHEME_DIR), ForgeConstants.DECK_BASE_DIR));
        }
        return scheme;
    }

    public IStorage<Deck> getPlane() {
        if (plane == null) {
            plane = new StorageImmediatelySerialized<Deck>("Planechase decks",
                    new DeckStorage(new File(ForgeConstants.DECK_PLANE_DIR), ForgeConstants.DECK_BASE_DIR));
        }
        return plane;
    }

    public IStorage<Deck> getCommander() {
        if (commander == null) {
            commander = new StorageImmediatelySerialized<Deck>("Commander decks",
                    new DeckStorage(new File(ForgeConstants.DECK_COMMANDER_DIR), ForgeConstants.DECK_BASE_DIR));
        }
        return commander;
    }

    public IStorage<Deck> getTinyLeaders() {
        if (tinyLeaders == null) {
            tinyLeaders = new StorageImmediatelySerialized<Deck>("Tiny Leaders decks",
                    new DeckStorage(new File(ForgeConstants.DECK_TINY_LEADERS_DIR), ForgeConstants.DECK_BASE_DIR));
        }
        return tinyLeaders;
    }
}
