package com.therapylocal.app.memory

import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

/**
 * Preloaded demo memory: clients with their sessions, facts and recurrence data, so the app can be shown without running the pipeline.
 * JSON format (version 1), also written by `scripts/export_demo.py` from a pulled `memory.db`:
 *
 *   {"version":1,"clients":[{"id":"george","name":"George","age":41,"occupation":"",
 *      "sessions":[{"id":"george_s01","date":"2026-01-14","chunks":[{"idx":1,"text":"...","vector":"<base64 float32 LE>"}]}],
 *      "facts":[{"id":"F1","category":"work","subject":"George","fact":"...","evidence":"...","chunk":1,"session":"george_s01","vector":"<base64>"}],
 *      "supports":[{"fact_id":"F1","session":"george_s03","evidence":"...","chunk":2}]}]}
 *
 * "vector" is optional everywhere (a fact without one is not found by embedding search); "age" and "occupation" are optional.
 */
object DemoMemory {
    data class Chunk(val idx: Int, val text: String, val vector: FloatArray?)
    data class Session(val id: String, val date: String, val chunks: List<Chunk>)
    data class DemoFact(val fact: Fact, val vector: FloatArray?)
    data class Client(
        val id: String, val name: String, val age: Int?, val occupation: String,
        val sessions: List<Session>, val facts: List<DemoFact>, val supports: List<MemoryStore.Support>,
    )
    data class Data(val version: Int, val clients: List<Client>)

    fun parse(json: String): Data {
        val root = JSONObject(json)
        val clients = root.getJSONArray("clients")
        return Data(
            root.optInt("version", 1),
            (0 until clients.length()).map { client(clients.getJSONObject(it)) },
        )
    }

    private fun client(o: JSONObject): Client {
        val id = o.getString("id")
        val sessions = o.optJSONArray("sessions")
        val facts = o.optJSONArray("facts")
        val supports = o.optJSONArray("supports")
        return Client(
            id, o.optString("name", id.replaceFirstChar { it.uppercase() }), if (o.has("age") && !o.isNull("age")) o.getInt("age") else null, o.optString("occupation"),
            (0 until (sessions?.length() ?: 0)).map { session(sessions!!.getJSONObject(it)) },
            (0 until (facts?.length() ?: 0)).map { f ->
                val x = facts!!.getJSONObject(f)
                DemoFact(
                    Fact(x.getString("id"), x.optString("category", "other"), x.optString("subject", id), x.getString("fact"), x.optString("evidence"), null, x.optString("session"), x.optInt("chunk", 0)),
                    vector(x),
                )
            },
            (0 until (supports?.length() ?: 0)).map { s ->
                val x = supports!!.getJSONObject(s)
                MemoryStore.Support(x.getString("fact_id"), x.getString("session"), x.optString("evidence"), x.optInt("chunk", 0))
            },
        )
    }

    private fun session(o: JSONObject): Session {
        val chunks = o.optJSONArray("chunks")
        return Session(
            o.getString("id"), o.optString("date"),
            (0 until (chunks?.length() ?: 0)).map { c -> chunks!!.getJSONObject(c).let { Chunk(it.getInt("idx"), it.getString("text"), vector(it)) } },
        )
    }

    private fun vector(o: JSONObject): FloatArray? = if (o.has("vector") && !o.isNull("vector")) decode(o.getString("vector")) else null

    fun decode(b64: String): FloatArray {
        val bb = ByteBuffer.wrap(Base64.getDecoder().decode(b64)).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(bb.remaining() / 4).also { bb.asFloatBuffer().get(it) }
    }

    fun encode(v: FloatArray): String {
        val bb = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        bb.asFloatBuffer().put(v)
        return Base64.getEncoder().encodeToString(bb.array())
    }

    /**
     * Replaces everything stored for each client in [data] with the demo content, in one transaction. Running it again gives the same state,
     * so it is safe to call on every toggle. Note it overwrites real data of a client with the same id.
     */
    fun import(store: MemoryStore, data: Data) = store.inTransaction {
        for (c in data.clients) {
            store.clearClient(c.id)
            store.deleteClientRow(c.id)
            store.createClient(c.id, c.name, c.age, c.occupation)
            for (s in c.sessions) {
                store.putSession(c.id, s.id, s.date)
                for (ch in s.chunks) store.putChunk(c.id, s.id, ch.idx, ch.text, ch.vector ?: FloatArray(0))
            }
            for (f in c.facts) store.putFact(c.id, f.fact, f.vector)
            for (s in c.supports) store.addSupport(c.id, s.factId, s.session, s.evidence, s.chunk)
        }
    }
}
