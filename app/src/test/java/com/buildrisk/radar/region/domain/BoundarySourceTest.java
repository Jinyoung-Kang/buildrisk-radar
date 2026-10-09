package com.buildrisk.radar.region.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BoundarySourceTest {
    @Test
    void 처음이면_키가_있는_출처로_이미_있으면_지금_출처를_그대로() {
        assertThat(BoundarySource.decide(null, false, List.of(), true, true)).isEqualTo(new BoundarySource.Decision("VWORLD", false));
        assertThat(BoundarySource.decide(null, false, List.of(), false, true)).isEqualTo(new BoundarySource.Decision("SGIS", false));
        assertThat(BoundarySource.decide(null, false, List.of("SGIS"), true, true)).isEqualTo(new BoundarySource.Decision("SGIS", false));
    }

    @Test
    void 키가_없다고_출처를_바꾸지_않고_바꾸려면_확인이_필요하다() {
        assertThatThrownBy(() -> BoundarySource.decide(null, false, List.of("VWORLD"), false, true)).hasMessageContaining("VWORLD_API_KEY");
        assertThatThrownBy(() -> BoundarySource.decide("SGIS", false, List.of("VWORLD"), false, true)).hasMessageContaining("confirmPurge");
        assertThat(BoundarySource.decide("SGIS", true, List.of("VWORLD"), false, true)).isEqualTo(new BoundarySource.Decision("SGIS", true));
        assertThatThrownBy(() -> BoundarySource.decide(null, false, List.of("SGIS", "VWORLD"), true, true)).hasMessageContaining("섞여");
        assertThatThrownBy(() -> BoundarySource.decide("SGIS", true, List.of(), true, false)).hasMessageContaining("SGIS_CONSUMER_KEY");
    }
}
