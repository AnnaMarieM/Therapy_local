package com.therapylocal.app.engine;

import android.os.Bundle;
import com.therapylocal.app.engine.IEngineEvents;

interface IEngineHost {
    /** The one generic call. Large payloads travel as SharedMemory inside the Bundle (see EngineCodec). Never throws a Binder exception for an engine failure. */
    Bundle call(in Bundle op);

    /** Client death link, the phase channel, and the log join (client pid and session id). */
    void attach(IBinder clientToken, IEngineEvents events, int clientPid, String clientSid);

    /** Ends the host process (:live before :batch, idle stop). */
    oneway void shutdown(String reason);
}
