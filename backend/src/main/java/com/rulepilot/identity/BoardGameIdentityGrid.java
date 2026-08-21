package com.rulepilot.identity;

import java.time.Instant;
import java.util.List;

public interface BoardGameIdentityGrid {

    enum Slot {
        FAVORITE_GAME,
        FAVORITE_ART,
        FAVORITE_DESIGNER,
        FAVORITE_MECHANISM,
        FAVORITE_THEME,
        FAVORITE_PUBLISHER,
        FAVORITE_EXPANSION,
        FAVORITE_COMPONENT,
        WISHLIST_GAME
    }

    List<Selection> read(String username);

    Selection select(
            String username,
            Slot slot,
            int bggId,
            String gameName,
            String chineseName,
            String thumbnailUrl,
            String imageUrl,
            Instant updatedAt);

    void clear(String username, Slot slot);

    record Selection(
            Slot slot,
            int bggId,
            String gameName,
            String chineseName,
            String thumbnailUrl,
            String imageUrl,
            Instant updatedAt) {}
}
