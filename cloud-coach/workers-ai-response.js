// GPT-OSS on the Workers AI binding uses the Responses API, not Chat Completions.
// https://developers.cloudflare.com/changelog/post/2025-08-05-openai-open-models/
export function toWorkersAIInput(parameters) {
  if (!parameters || !Array.isArray(parameters.messages) || !parameters.messages.length)
    throw new TypeError("Workers AI requires input messages");
  const input = parameters.messages.map(message => {
    if (!message || !["system", "user", "assistant"].includes(message.role) || typeof message.content !== "string")
      throw new TypeError("Workers AI message must contain a supported role and text");
    return { role: message.role, content: message.content };
  });
  const effort = parameters.reasoning_effort ?? "low";
  if (!["low", "medium", "high"].includes(effort)) throw new TypeError("Invalid reasoning effort");
  if (!Number.isSafeInteger(parameters.max_tokens) || parameters.max_tokens <= 0)
    throw new TypeError("Invalid output token budget");
  // Deliberately forward only the parameters used by this service. Never enable tools implicitly.
  const converted = { input, max_output_tokens: parameters.max_tokens, reasoning: { effort } };
  if (parameters.temperature !== undefined) {
    if (!Number.isFinite(parameters.temperature) || parameters.temperature < 0 || parameters.temperature > 2)
      throw new TypeError("Invalid temperature");
    converted.temperature = parameters.temperature;
  }
  return converted;
}

/** Extract only completed assistant-visible text. Reasoning and tool payloads are never answers. */
export function extractWorkersAIText(result, maxBytes = 128 * 1024) {
  if (!Number.isSafeInteger(maxBytes) || maxBytes <= 0) return "";
  const bounded = value => typeof value === "string" && value.trim() &&
    new TextEncoder().encode(value).length <= maxBytes ? value : "";
  const failed = value => value.error != null || value.refusal != null || value.incomplete_details != null ||
    value.status !== undefined && value.status !== "completed";
  if (typeof result === "string") return bounded(result);
  if (!result || typeof result !== "object" || Array.isArray(result) || failed(result)) return "";

  if (Object.hasOwn(result, "output")) {
    if (!Array.isArray(result.output)) return "";
    const text = [];
    let bytes = 0;
    for (const item of result.output) {
      if (!item || typeof item !== "object" || Array.isArray(item) || failed(item)) return "";
      if (item.type === "reasoning") continue;
      // We never requested tools. Unknown items, refusals, and function/tool calls fail closed.
      if (item.type !== "message" || item.role !== "assistant" || !Array.isArray(item.content)) return "";
      for (const part of item.content) {
        if (!part || part.type !== "output_text" || typeof part.text !== "string" || part.refusal != null)
          return "";
        bytes += new TextEncoder().encode(part.text).length + (text.length ? 1 : 0);
        if (bytes > maxBytes) return "";
        text.push(part.text);
      }
    }
    return bounded(text.join("\n"));
  }
  if (typeof result.response === "string") return bounded(result.response);
  if (result.result && typeof result.result === "object" && !Array.isArray(result.result))
    return failed(result.result) ? "" : bounded(result.result.response);
  if (typeof result.output_text === "string") return bounded(result.output_text);
  if (Array.isArray(result.choices) && result.choices.length === 1) {
    const choice = result.choices[0];
    const message = choice?.message;
    if (!choice || failed(choice) || choice.finish_reason !== undefined && choice.finish_reason !== "stop" ||
      !message || failed(message) || message.role !== undefined && message.role !== "assistant" ||
      message.tool_calls != null || message.function_call != null) return "";
    return bounded(message.content);
  }
  return "";
}
