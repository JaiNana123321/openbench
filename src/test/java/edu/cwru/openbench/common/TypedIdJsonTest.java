package edu.cwru.openbench.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import org.junit.jupiter.api.Test;

/** Typed ids are bare JSON numbers in any mapper, through the annotations on the records. */
class TypedIdJsonTest {

    private final ObjectMapper json = new ObjectMapper();

    record Line(RequestLineId id, RequestId request, ResourceTypeId type) {}

    @Test
    void writesAnIdAsABareNumber() throws Exception {
        assertThat(json.writeValueAsString(new TeamId(7))).isEqualTo("7");
        assertThat(json.writeValueAsString(new Line(new RequestLineId(3), new RequestId(42), new ResourceTypeId(2))))
                .isEqualTo("{\"id\":3,\"request\":42,\"type\":2}");
    }

    @Test
    void readsAnIdFromABareNumber() throws Exception {
        assertThat(json.readValue("7", MemberId.class)).isEqualTo(new MemberId(7));
        assertThat(json.readValue("{\"id\":3,\"request\":42,\"type\":2}", Line.class))
                .isEqualTo(new Line(new RequestLineId(3), new RequestId(42), new ResourceTypeId(2)));
    }

    @Test
    void refusesAnIdWrittenAsAnObject() {
        assertThatThrownBy(() -> json.readValue("{\"value\":7}", TeamId.class))
                .isInstanceOf(MismatchedInputException.class);
    }

    @Test
    void idsOfDifferentTypesAreNeverEqual() {
        assertThat((Object) new TeamId(1)).isNotEqualTo(new MemberId(1));
    }
}
