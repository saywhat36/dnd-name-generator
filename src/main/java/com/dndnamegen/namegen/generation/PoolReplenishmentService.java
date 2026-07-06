package com.dndnamegen.namegen.generation;

import com.dndnamegen.namegen.config.PromptTemplateConfig;
import com.dndnamegen.namegen.name.Gender;
import com.dndnamegen.namegen.name.NameInsertDao;
import com.dndnamegen.namegen.name.NameRepository;
import com.dndnamegen.namegen.name.NameSource;
import com.dndnamegen.namegen.name.NameStatus;
import com.dndnamegen.namegen.name.Race;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Owns the async, threshold-triggered pool replenishment cycle described in
 * docs/ARCHITECTURE.md's "Replenishment flow" section: stampede guard, global
 * LLM budget check, per-combo pool cap check, generation (delegated to
 * NameGenerationService, which owns its own bounded retry/quality-gate/dedup
 * pipeline and per-attempt generation_log writes), the native insert path
 * (NameInsertDao), and a guaranteed outcome log for this replenishment cycle
 * itself.
 *
 * <p>Callers decide *when* to trigger replenishment (the "threshold" in
 * "threshold-triggered") -- that wiring lands in NameService in the next
 * Week 3 slice, once the CURATED/AI_GENERATED/BOTH source toggle exists.
 * This class only decides whether a triggered call should actually generate
 * anything, via the budget/cap/examples checks below.
 */
@Service
public class PoolReplenishmentService {

    private static final Logger log = LoggerFactory.getLogger(PoolReplenishmentService.class);

    private final NameGenerationService nameGenerationService;
    private final NameRepository nameRepository;
    private final NameInsertDao nameInsertDao;
    private final GenerationLogRepository generationLogRepository;
    private final int poolCapPerCombo;
    private final int batchSize;
    private final int maxGenerationCallsPerDay;
    private final String provider;
    private final String model;

    private final ConcurrentHashMap<ComboKey, Boolean> inFlightCombos = new ConcurrentHashMap<>();
    private final Object budgetLock = new Object();
    private LocalDate budgetDay = LocalDate.now();
    private final AtomicInteger generationCallsUsedToday = new AtomicInteger(0);

    public PoolReplenishmentService(
            NameGenerationService nameGenerationService,
            NameRepository nameRepository,
            NameInsertDao nameInsertDao,
            GenerationLogRepository generationLogRepository,
            @Value("${app.pool-replenishment.cap-per-combo:20}") int poolCapPerCombo,
            @Value("${app.pool-replenishment.batch-size:5}") int batchSize,
            @Value("${app.pool-replenishment.budget.max-calls-per-day:200}") int maxGenerationCallsPerDay,
            @Value("${app.generation.provider:unknown}") String provider,
            @Value("${app.generation.model:unknown}") String model) {
        this.nameGenerationService = nameGenerationService;
        this.nameRepository = nameRepository;
        this.nameInsertDao = nameInsertDao;
        this.generationLogRepository = generationLogRepository;
        this.poolCapPerCombo = poolCapPerCombo;
        this.batchSize = batchSize;
        this.maxGenerationCallsPerDay = maxGenerationCallsPerDay;
        this.provider = provider;
        this.model = model;
    }

    /**
     * Triggers a replenishment attempt for one race/gender combo. Safe to call
     * from multiple concurrent requests below-threshold for the same combo --
     * only the first claims the in-flight lock and actually generates; the
     * rest return immediately (see docs/ARCHITECTURE.md, "stampede guard").
     */
    @Async("poolReplenishmentExecutor")
    public void replenish(Race race, Gender gender) {
        ComboKey combo = new ComboKey(race, gender);
        if (inFlightCombos.putIfAbsent(combo, Boolean.TRUE) != null) {
            log.debug("Replenishment already in flight for {}/{}, skipping", race, gender);
            return;
        }
        try {
            doReplenish(race, gender);
        } finally {
            inFlightCombos.remove(combo);
        }
    }

    /**
     * Every path through this method writes exactly one generation_log row
     * describing this replenishment cycle's outcome, at the single point each
     * branch actually completes -- there is no shared "did we already log
     * this?" flag to keep in sync, since each branch either returns
     * immediately after its own saveSkip call or falls through to the single
     * success-path save. This mirrors the per-attempt logging
     * NameGenerationService already does internally, but at the outer,
     * one-cycle granularity: skip reasons (budget/cap/no-examples) have no
     * other place to be recorded, since NameGenerationService is never called
     * for them.
     */
    private void doReplenish(Race race, Gender gender) {
        int requested = 0;
        try {
            // Pool-cap and curated-examples checks run before the budget check -- a deliberate
            // reordering from docs/ARCHITECTURE.md's literal step list (which puts the budget check
            // first). Consuming budget before these checks would count combos that were never going to
            // generate anyway (already at cap, or no style anchor to generate from) against the daily
            // limit, starving combos that actually need it. Budget is only consumed immediately before
            // the real generation call below.
            long poolSize = nameRepository.countByRaceAndGenderAndStatusAndSource(
                    race, gender, NameStatus.ACTIVE, NameSource.AI_GENERATED);
            if (poolSize >= poolCapPerCombo) {
                saveSkip(race, gender, requested, "pool at cap (%d/%d)".formatted(poolSize, poolCapPerCombo));
                return;
            }

            long curatedExampleCount = nameRepository.countByRaceAndGenderAndStatusAndSource(
                    race, gender, NameStatus.ACTIVE, NameSource.CURATED);
            if (curatedExampleCount == 0) {
                // See https://github.com/saywhat36/dnd-name-generator/issues/18 -- generating with no
                // CURATED few-shot examples sends a hollow "match these names" prompt with nothing to
                // match. Skipping here (rather than generating anyway) is the fix that issue deferred to
                // this class.
                saveSkip(race, gender, requested, "no CURATED examples for this combo, skipping generation");
                return;
            }

            if (!tryConsumeBudget()) {
                saveSkip(race, gender, requested, "global LLM budget exhausted for today");
                return;
            }

            requested = (int) Math.min(batchSize, poolCapPerCombo - poolSize);
            List<String> survivors = nameGenerationService.generateValidatedNames(race, gender, requested);

            GenerationLog insertLog = generationLogRepository.save(GenerationLog.success(
                    race,
                    gender,
                    GenerationMode.STANDARD,
                    PromptTemplateConfig.NAME_GENERATION_PROMPT_VERSION,
                    requested,
                    survivors.size(),
                    0,
                    0));

            try {
                int inserted = nameInsertDao.insertGenerated(
                        race,
                        gender,
                        survivors,
                        provider,
                        model,
                        PromptTemplateConfig.NAME_GENERATION_PROMPT_VERSION,
                        insertLog.getId());
                log.info(
                        "Replenished {}/{}: requested {}, generated {}, inserted {} (conflicts dropped: {})",
                        race,
                        gender,
                        requested,
                        survivors.size(),
                        inserted,
                        survivors.size() - inserted);
            } catch (RuntimeException insertFailure) {
                // Deliberately not a second generation_log row: the row saved above already records
                // what NameGenerationService produced (requested/accepted after quality-gate/dedup),
                // which genuinely succeeded. A failure here is a DB/infrastructure failure inserting
                // already-validated names -- generation_log audits generation outcomes, not insert
                // infrastructure, so this is surfaced via the error log instead. Writing a second row
                // here would make one replenishment cycle produce two generation_log rows.
                log.error("Insert failed after successful generation for {}/{}", race, gender, insertFailure);
            }
        } catch (RuntimeException e) {
            log.error("Replenishment failed for {}/{}", race, gender, e);
            saveSkip(race, gender, requested, "replenishment failed: " + e.getMessage());
        }
    }

    private void saveSkip(Race race, Gender gender, int requested, String reason) {
        generationLogRepository.save(GenerationLog.parseFailure(
                race, gender, GenerationMode.STANDARD, PromptTemplateConfig.NAME_GENERATION_PROMPT_VERSION,
                requested, reason));
    }

    /**
     * Single-instance in-memory day counter -- adequate for now since the
     * stampede guard above is also single-instance in-memory; both would need
     * a shared store (e.g. Postgres advisory lock / a counter row) if this
     * ever runs on more than one instance, per docs/ARCHITECTURE.md.
     */
    private boolean tryConsumeBudget() {
        synchronized (budgetLock) {
            LocalDate today = LocalDate.now();
            if (!today.equals(budgetDay)) {
                budgetDay = today;
                generationCallsUsedToday.set(0);
            }
            if (generationCallsUsedToday.get() >= maxGenerationCallsPerDay) {
                return false;
            }
            generationCallsUsedToday.incrementAndGet();
            return true;
        }
    }

    private record ComboKey(Race race, Gender gender) {}
}
