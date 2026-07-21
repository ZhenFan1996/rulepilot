package com.rulepilot.teaching;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.teaching.TeachingOutlineModel.OutlineRequest;
import com.rulepilot.teaching.TeachingOutlineModel.PageImageInput;
import com.rulepilot.teaching.TeachingOutlineModel.PageInput;
import java.util.List;
import org.junit.jupiter.api.Test;

class TeachingOutlineModelTest {

    @Test
    void preservesPageImageEvidenceWithoutLeakingMutableBytes() {
        byte[] source = {1, 2, 3};

        var request = new OutlineRequest(
                3, 2, 45, List.of(new PageInput(1, "visual evidence")), List.of(new PageImageInput(1, "image/jpeg", source)));
        source[0] = 9;
        byte[] exposed = request.pageImages().getFirst().content();
        exposed[1] = 9;

        assertThat(request.pageImages().getFirst().content()).containsExactly(1, 2, 3);
    }
}
