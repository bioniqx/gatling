package com.rgp.loadtest.core.protocol;

import com.luigi.gaas.common.data.PuObject;
import com.luigi.gaas.common.data.msgpkg.MarioBytesCodec;
import java.util.Collections;
import java.util.Map;

/**
 * MessagePack bridge using the proprietary GaaS library so the wire format matches what the
 * backend's plugin layer expects exactly. Thread-safe — share one instance.
 *
 * <p>Used by {@code BonanzaGrpcSimulation} to encode {@code ConnectAndCallRequest.user.parameters}
 * (the join payload: agentId/accessToken/uid/username/…) and {@code PluginRequest.data} (per-call
 * payload: cmd/betAmount/isTrial). The backend's {@code UserContext.fromPuObject} decodes this
 * via the same library, so wire-format drift here will NPE on the backend side.
 */
public final class Codec {

  public byte[] encode(Map<String, Object> data) {
    return PuObject.fromObject(data).toBytes();
  }

  @SuppressWarnings("unchecked")
  public Map<String, Object> decodeToMap(byte[] data) {
    if (data == null || data.length == 0) {
      return Collections.emptyMap();
    }
    try {
      Object unpacked = MarioBytesCodec.unpack(data);
      if (unpacked instanceof Map) {
        return (Map<String, Object>) unpacked;
      }
      if (unpacked instanceof PuObject puObject) {
        return puObject.toMap();
      }
      return Collections.emptyMap();
    } catch (Exception e) {
      return Collections.emptyMap();
    }
  }
}
