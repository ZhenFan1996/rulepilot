package com.rulepilot.teaching.adapter.out.model;

import com.rulepilot.teaching.TeachingOutlineModel;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class FakeTeachingOutlineModel implements TeachingOutlineModel {

    @Override
    public OutlineDraft organize(OutlineRequest request) {
        return new OutlineDraft(
                "Imported rulebook",
                "Learn the table setup, the repeating turn loop, and how the game ends and scores.",
                List.of(
                        topic("prepare-the-table", "Prepare the table", "Explain setup in table order.", "setup", "setup starting components table"),
                        topic("play-the-game", "What happens on a turn", "Explain the core loop and choices.", "core_loop", "turn round action player"),
                        new TopicDraft(
                                "finish-and-score",
                                "Finish the game and determine the winner",
                                "Explain the end trigger and every final scoring step.",
                                true,
                                List.of("end game final round", "final scoring winner"),
                                List.of("end", "scoring"))));
    }

    private TopicDraft topic(String key, String title, String objective, String tag, String query) {
        return new TopicDraft(key, title, objective, true, List.of(query), List.of(tag));
    }
}
