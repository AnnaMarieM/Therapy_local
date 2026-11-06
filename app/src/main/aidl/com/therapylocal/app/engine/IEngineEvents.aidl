package com.therapylocal.app.engine;

/** Host to main. Synchronous on purpose: main notes the native step in its guard before the host runs it. */
interface IEngineEvents {
    void phase(String req, String loadId, String model, String backend, String phase);
}
