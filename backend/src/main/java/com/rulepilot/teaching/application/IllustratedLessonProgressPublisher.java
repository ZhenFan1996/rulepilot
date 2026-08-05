package com.rulepilot.teaching.application;

import com.rulepilot.teaching.domain.IllustratedLesson;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!test")
public class IllustratedLessonProgressPublisher {

    private final IllustratedLessonRepository lessons;

    public IllustratedLessonProgressPublisher(IllustratedLessonRepository lessons) {
        this.lessons = lessons;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public IllustratedLesson publish(IllustratedLesson lesson) {
        return lessons.save(lesson);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public IllustratedLesson publishCandidate(IllustratedLesson lesson) {
        return lessons.saveCandidate(lesson);
    }
}
