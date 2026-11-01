package com.quant.strategy.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** strategy_bots / strategy_settings 存取（JdbcTemplate；表由 Flyway V4 建）。 */
@Repository
public class BotRepository {

    private static final ObjectMapper M = new ObjectMapper();
    private final JdbcTemplate jdbc;
    public BotRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record BotRow(String id, String type, String name, String symbol, Map<String, Object> params,
                         double allocation, boolean running, boolean builtin) {}

    public void upsert(BotRow b) {
        jdbc.update("""
            INSERT INTO strategy_bots (id, type, name, symbol, params, allocation, running, builtin, updated_at)
            VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?, now())
            ON CONFLICT (id) DO UPDATE SET type = EXCLUDED.type, name = EXCLUDED.name, symbol = EXCLUDED.symbol,
              params = EXCLUDED.params, allocation = EXCLUDED.allocation, running = EXCLUDED.running,
              builtin = EXCLUDED.builtin, updated_at = now()
            """, b.id(), b.type(), b.name(), b.symbol(), json(b.params()), b.allocation(), b.running(), b.builtin());
    }

    public void setRunning(String id, boolean running) {
        jdbc.update("UPDATE strategy_bots SET running = ?, updated_at = now() WHERE id = ?", running, id);
    }
    public void setAllocation(String id, double a) {
        jdbc.update("UPDATE strategy_bots SET allocation = ?, updated_at = now() WHERE id = ?", a, id);
    }
    public void setParams(String id, Map<String, Object> p) {
        jdbc.update("UPDATE strategy_bots SET params = ?::jsonb, updated_at = now() WHERE id = ?", json(p), id);
    }
    public void delete(String id) { jdbc.update("DELETE FROM strategy_bots WHERE id = ?", id); }

    public List<BotRow> findAll() {
        return jdbc.query("SELECT id, type, name, symbol, params::text AS params, allocation, running, builtin FROM strategy_bots ORDER BY id",
                (rs, i) -> new BotRow(rs.getString("id"), rs.getString("type"), rs.getString("name"), rs.getString("symbol"),
                        parse(rs.getString("params")), rs.getDouble("allocation"), rs.getBoolean("running"), rs.getBoolean("builtin")));
    }

    public Optional<String> setting(String key) {
        List<String> r = jdbc.query("SELECT value FROM strategy_settings WHERE key = ?", (rs, i) -> rs.getString(1), key);
        return r.isEmpty() ? Optional.empty() : Optional.of(r.get(0));
    }
    public void setSetting(String key, String value) {
        jdbc.update("INSERT INTO strategy_settings (key, value, updated_at) VALUES (?, ?, now()) ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = now()", key, value);
    }

    static String json(Map<String, Object> p) { try { return M.writeValueAsString(p == null ? Map.of() : p); } catch (Exception e) { return "{}"; } }
    static Map<String, Object> parse(String s) {
        try { return s == null ? Map.of() : M.readValue(s, new TypeReference<Map<String, Object>>() {}); } catch (Exception e) { return Map.of(); }
    }
}
