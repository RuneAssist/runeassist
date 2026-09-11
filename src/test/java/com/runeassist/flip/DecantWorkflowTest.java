package com.runeassist.flip;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.runeassist.flip.model.DecantPlan;
import com.runeassist.flip.model.ComposeSuggestionMapper;
import com.runeassist.flip.model.ComposeSuggestionResponse;
import com.runeassist.flip.model.SuggestionType;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class DecantWorkflowTest {
    private DecantPlan plan() {
        DecantPlan p = new DecantPlan();
        p.setVersion(1); p.setBuyItemId(3); p.setSellItemId(4); p.setBuyDose(3); p.setSellDose(4);
        p.setBuyQty(400); p.setSellQty(300); p.setBuyAt(3000); p.setSellAt(6000);
        p.setBuyName("Prayer potion(3)"); p.setSellName("Prayer potion(4)");
        p.setFamilyIds(Arrays.asList(1, 2, 3, 4));
        return p;
    }
    @Test void validPlanConservesDoses() {
        DecantPlan p = plan(); assertTrue(p.isValid());
        p.setSellQty(301); assertFalse(p.isValid());
        p = plan(); p.setSellDose(2); assertFalse(p.isValid());
        p = plan(); p.setFamilyIds(Arrays.asList(1, 2, 3)); assertFalse(p.isValid());
    }
    @Test void onlyExactObservedConversionQualifies() {
        DecantPlan p = plan();
        assertTrue(DecantWorkflow.matches(p, Map.of("3", 400), Map.of("4", 300)));
        assertTrue(DecantWorkflow.matches(p, Map.of("3", 4), Map.of("4", 3)));
        assertFalse(DecantWorkflow.matches(p, Map.of("3", 400), Map.of())); // banked/disappeared
        assertFalse(DecantWorkflow.matches(p, Map.of("3", 400), Map.of("3", 400, "4", 300))); // unrelated output
        assertFalse(DecantWorkflow.matches(p, Map.of("3", 3), Map.of("4", 2, "1", 1))); // ambiguous remainder
        assertFalse(DecantWorkflow.matches(p, Map.of("3", 400), Map.of("4", 300, "1", 1)));
        assertFalse(DecantWorkflow.matches(p, Map.of("3", 800), Map.of("4", 600))); // exceeds plan
    }
    @Test void pendingEvidenceAndPlanSurviveRestartSerialization() {
        Gson gson = new Gson(); DecantWorkflow.State s = new DecantWorkflow.State();
        s.plan = plan(); s.started = true; s.pending = new JsonObject();
        s.pending.addProperty("id", "one-stable-event-id");
        DecantWorkflow.State restored = gson.fromJson(gson.toJson(s), DecantWorkflow.State.class);
        assertTrue(restored.plan.isValid()); assertTrue(restored.started); assertFalse(restored.converted);
        assertEquals("one-stable-event-id", restored.pending.get("id").getAsString());
    }
    @Test void mapperPreservesPlanAndRejectsMalformedPlans() {
        ComposeSuggestionResponse.SuggestionDto dto = new ComposeSuggestionResponse.SuggestionDto();
        dto.setType("decant"); dto.setItemId(3); dto.setQuantity(400); dto.setDecantPlan(plan());
        assertEquals(SuggestionType.DECANT, ComposeSuggestionMapper.toSuggestion(dto, "ares").getType());
        assertEquals(4, ComposeSuggestionMapper.toSuggestion(dto, "ares").getDecantPlan().getSellItemId());
        dto.getDecantPlan().setSellQty(301); assertNull(ComposeSuggestionMapper.toSuggestion(dto, "ares"));
    }
}
