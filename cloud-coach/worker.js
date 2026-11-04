const WORKERS_AI_MODEL = "@cf/meta/llama-3.3-70b-instruct-fp8-fast";

export default {
  async fetch(request, env) {
    try {
      const url = new URL(request.url);

      if (request.method === "GET" && url.pathname === "/") {
        return jsonResponse({
          service: "THOOP Cloud Coach",
          status: "ok",
          memory: Boolean(env.DB),
          aiConfigured: Boolean(env.AI),
          model: WORKERS_AI_MODEL,
        });
      }

      if (request.method !== "POST" || url.pathname !== "/v1/coach/chat") {
        return jsonResponse({ error: "Not found" }, 404);
      }

      if (!env.DB) {
        return jsonResponse(
          {
            error: "Cloud memory database is not configured",
            retryable: false,
          },
          503,
        );
      }

      if (!env.AI) {
        return jsonResponse(
          {
            error: "Workers AI binding is not configured",
            retryable: false,
          },
          503,
        );
      }

      const contentType = request.headers.get("content-type") || "";
      if (!contentType.toLowerCase().includes("application/json")) {
        return jsonResponse(
          { error: "Content-Type must be application/json" },
          415,
        );
      }

      const body = await request.json();
      const rawUserId = cleanText(body.userId, 200);
      const question = cleanText(body.question, 8000);

      if (!rawUserId) {
        return jsonResponse({ error: "userId is required" }, 400);
      }

      if (!question) {
        return jsonResponse({ error: "question is required" }, 400);
      }

      const userId = await sha256(rawUserId);
      const conversationId =
        cleanText(body.conversationId, 100) || crypto.randomUUID();
      const healthContext = cleanText(body.healthContext, 30000);
      const systemPrompt = cleanText(body.systemPrompt, 12000);
      const recentMessages = normalizeRecentMessages(body.recentMessages);

      const conversation = await env.DB
        .prepare(`
          SELECT summary
          FROM coach_conversations
          WHERE user_id = ?
            AND conversation_id = ?
          LIMIT 1
        `)
        .bind(userId, conversationId)
        .first();

      const memoriesResult = await env.DB
        .prepare(`
          SELECT category, memory_key, value
          FROM coach_memories
          WHERE user_id = ?
            AND active = 1
          ORDER BY updated_at DESC
          LIMIT 50
        `)
        .bind(userId)
        .all();

      const memories = (memoriesResult.results || []).map((memory) => ({
        category: cleanText(memory.category, 40),
        key: cleanText(memory.memory_key, 80),
        value: cleanText(memory.value, 500),
      }));

      const conversationSummary = cleanText(conversation?.summary, 6000);
      const nutritionReferences = await lookupNutritionReferences(question);
      const prompt = buildCoachPrompt({
        systemPrompt,
        healthContext,
        memories,
        conversationSummary,
        recentMessages,
        question,
        nutritionReferences,
      });

      const aiOutput = await callWorkersAI(env, prompt);
      const answer = cleanText(aiOutput.answer, 16000);

      if (!answer) {
        throw new Error("Workers AI returned an empty answer");
      }

      const updatedSummary = cleanText(aiOutput.conversationSummary, 6000);
      const timestamp = new Date().toISOString();

      await env.DB
        .prepare(`
          INSERT INTO coach_conversations (
            user_id,
            conversation_id,
            summary,
            updated_at
          )
          VALUES (?, ?, ?, ?)
          ON CONFLICT(user_id, conversation_id)
          DO UPDATE SET
            summary = excluded.summary,
            updated_at = excluded.updated_at
        `)
        .bind(userId, conversationId, updatedSummary, timestamp)
        .run();

      const proposedMemories = Array.isArray(aiOutput.memories)
        ? aiOutput.memories.slice(0, 3)
        : [];

      for (const memory of proposedMemories) {
        const category = normalizeMemoryCategory(memory?.category);
        const memoryKey = cleanText(memory?.key, 80).toLowerCase();
        const value = cleanText(memory?.value, 500);

        if (!category || !memoryKey || !value) {
          continue;
        }

        await env.DB
          .prepare(`
            INSERT INTO coach_memories (
              user_id,
              category,
              memory_key,
              value,
              active,
              updated_at
            )
            VALUES (?, ?, ?, ?, 1, ?)
            ON CONFLICT(user_id, category, memory_key)
            DO UPDATE SET
              value = excluded.value,
              active = 1,
              updated_at = excluded.updated_at
          `)
          .bind(userId, category, memoryKey, value, timestamp)
          .run();
      }

      return jsonResponse({
        answer,
        conversationId,
        nutrition: normalizeNutrition(aiOutput.nutrition),
      });
    } catch (error) {
      console.error("THOOP Cloud Coach error", error);

      const message = error instanceof Error ? error.message : String(error);
      const lower = message.toLowerCase();
      const retryable =
        lower.includes("rate limit") ||
        lower.includes("quota") ||
        lower.includes("temporarily") ||
        lower.includes("timeout") ||
        lower.includes("overloaded") ||
        lower.includes("capacity") ||
        lower.includes("429") ||
        lower.includes("503");

      return jsonResponse(
        {
          error: friendlyError(error),
          retryable,
        },
        retryable ? 503 : 500,
      );
    }
  },
};

function buildCoachPrompt({
  systemPrompt,
  healthContext,
  memories,
  conversationSummary,
  recentMessages,
  question,
  nutritionReferences,
}) {
  return [
    "You are THOOP Cloud Coach.",
    "",
    "Provide practical, concise, evidence-aware fitness coaching.",
    "Use THOOP measurements as the factual source for health metrics.",
    "Never invent measurements, missing history, diagnoses, or medical facts.",
    "Clearly distinguish observed THOOP data from interpretation.",
    "Do not recommend unsafe exercise when the available context indicates poor recovery, injury, illness, or another relevant constraint.",
    "Respond in the same language used by the user.",
    "",
    "SYSTEM INSTRUCTION:",
    systemPrompt || "(none)",
    "",
    "CURRENT THOOP HEALTH CONTEXT:",
    healthContext || "(not shared)",
    "",
    "DURABLE CLOUD MEMORY:",
    JSON.stringify(memories),
    "",
    "PREVIOUS CONVERSATION SUMMARY:",
    conversationSummary || "(none)",
    "",
    "RECENT CONVERSATION:",
    JSON.stringify(recentMessages),
    "",
    "CURRENT USER QUESTION:",
    question,
    "",
    "INTERNET NUTRITION REFERENCES (may be empty; verify relevance before using):",
    nutritionReferences || "(no matching structured reference found)",
    "",
    "Update conversationSummary so the next session can continue naturally.",
    "Extract no more than three durable memories.",
    "Only extract memory from explicit statements made by the user.",
    "Allowed memory categories: goal, preference, lifestyle, constraint, event, coach_style.",
    "Do not store daily health measurements, AI assumptions, inferred diagnoses, temporary metric values, or statements made only by the assistant.",
  ].join("\n");
}

async function callWorkersAI(env, prompt) {
  if (!env.AI) {
    throw new Error("Workers AI binding is not configured");
  }

  const outputInstruction = [
    "Return ONLY one valid JSON object.",
    "Do not use markdown or code fences.",
    "Do not include text before or after the JSON object.",
    "Stop immediately after the final closing brace.",
    "Do not generate JavaScript, comments, explanations, or examples.",
    'The first character must be "{" and the final character must be "}".',
    "",
    "Return exactly this structure:",
    "{",
    '  "answer": "string",',
    '  "conversationSummary": "string",',
    '  "memories": [',
    "    {",
    '      "category": "goal | preference | lifestyle | constraint | event | coach_style",',
    '      "key": "string",',
    '      "value": "string"',
    "    }",
    "  ],",
    '  "nutrition": null OR {',
    '    "foodName": "string",',
    '    "servingDescription": "string",',
    '    "servingGrams": number or null,',
    '    "caloriesKcal": number,',
    '    "minimumCaloriesKcal": number or null,',
    '    "maximumCaloriesKcal": number or null,',
    '    "proteinGrams": number or null,',
    '    "carbohydrateGrams": number or null,',
    '    "fatGrams": number or null,',
    '    "fiberGrams": number or null,',
    '    "confidence": "high | medium | low",',
    '    "assumptions": ["string"],',
    '    "sources": ["string"]',
    "  }",
    "}",
    "",
    "Rules:",
    "- answer must directly answer the current question.",
    "- Use the same language as the user.",
    "- Ground health statements only in the supplied THOOP context.",
    "- Never invent measurements, diagnoses, or missing history.",
    "- conversationSummary must be short and factual.",
    "- memories must always be an array.",
    "- Use an empty memories array when nothing durable should be remembered.",
    "- Only create memories from explicit user statements.",
    "- Never store daily measurements or temporary metric values as memories.",
    "- Set nutrition to a structured estimate only when the current user message describes food eaten/consumed or explicitly asks to estimate/log food.",
    "- Otherwise set nutrition to null; normal conversation must remain normal conversation.",
    "- Nutrition values must be finite and non-negative. Use a range and lower confidence when recipe or portion is uncertain.",
    "- Prefer relevant internet references supplied in the prompt; never claim a source that was not supplied.",
  ].join("\n");

  const fullPrompt = [
    outputInstruction,
    "",
    "THOOP COACH INPUT:",
    prompt,
  ].join("\n");

  let result;

  try {
    result = await env.AI.run(WORKERS_AI_MODEL, {
      prompt: fullPrompt,
      max_tokens: 600,
      temperature: 0.2,
    });
  } catch (error) {
    console.error("Workers AI inference failure", error);
    throw error;
  }

  const rawOutput =
    typeof result === "string"
      ? result.trim()
      : typeof result?.response === "string"
        ? result.response.trim()
        : typeof result?.result?.response === "string"
          ? result.result.response.trim()
          : "";

  if (!rawOutput) {
    console.error(
      "Workers AI returned an empty response",
      JSON.stringify(result).slice(0, 1000),
    );
    throw new Error("Workers AI returned an empty response");
  }

  const parsed = parseCoachOutput(rawOutput);
  const answer = cleanText(parsed.answer, 16000);

  if (!answer) {
    throw new Error("Workers AI returned an empty answer");
  }

  const conversationSummary = cleanText(parsed.conversationSummary, 6000);
  const memories = Array.isArray(parsed.memories)
    ? parsed.memories
        .slice(0, 12)
        .map((memory) => {
          const category = normalizeMemoryCategory(memory?.category);
          const key = cleanText(memory?.key, 80).toLowerCase();
          const value = cleanText(memory?.value, 500);

          if (!category || !key || !value) {
            return null;
          }

          return { category, key, value };
        })
        .filter(Boolean)
    : [];

  return {
    answer,
    conversationSummary,
    memories,
    nutrition: parsed?.nutrition ?? null,
  };
}

function parseCoachOutput(rawOutput) {
  const text = String(rawOutput || "")
    .replace(/^```(?:json)?\s*/i, "")
    .replace(/\s*```$/i, "")
    .trim();

  try {
    return JSON.parse(text);
  } catch (_) {
    // Continue and extract the first complete JSON object.
  }

  let start = -1;
  let depth = 0;
  let inString = false;
  let escaped = false;

  for (let index = 0; index < text.length; index += 1) {
    const character = text[index];

    if (start < 0) {
      if (character === "{") {
        start = index;
        depth = 1;
      }
      continue;
    }

    if (inString) {
      if (escaped) {
        escaped = false;
      } else if (character === "\\") {
        escaped = true;
      } else if (character === '"') {
        inString = false;
      }
      continue;
    }

    if (character === '"') {
      inString = true;
      continue;
    }

    if (character === "{") {
      depth += 1;
      continue;
    }

    if (character === "}") {
      depth -= 1;

      if (depth === 0) {
        const candidate = text.slice(start, index + 1);

        try {
          const parsed = JSON.parse(candidate);
          if (parsed && typeof parsed === "object") {
            return parsed;
          }
        } catch (_) {
          start = -1;
          depth = 0;
        }
      }
    }
  }

  console.warn(
    "Workers AI returned non-JSON output; using plain text as answer",
    text.slice(0, 1000),
  );

  return {
    answer: text.slice(0, 16000),
    conversationSummary: "",
    memories: [],
  };
}

async function lookupNutritionReferences(question) {
  const query = cleanText(question, 300);
  if (!query || !/[a-zA-Z\u00C0-\u024F\u1E00-\u1EFF]/u.test(query)) return "";
  const url = new URL("https://world.openfoodfacts.org/cgi/search.pl");
  url.searchParams.set("search_terms", query);
  url.searchParams.set("search_simple", "1");
  url.searchParams.set("action", "process");
  url.searchParams.set("json", "1");
  url.searchParams.set("page_size", "5");
  url.searchParams.set("fields", "product_name,serving_size,nutriments,url");
  try {
    const response = await fetch(url.toString(), {
      headers: { "User-Agent": "THOOP-Cloud-Coach/1.0" },
      cf: { cacheTtl: 3600, cacheEverything: true },
    });
    if (!response.ok) return "";
    const payload = await response.json();
    return (payload.products || []).slice(0, 5).map((product) => {
      const nutrients = product.nutriments || {};
      return JSON.stringify({
        source: "Open Food Facts",
        name: cleanText(product.product_name, 160),
        serving: cleanText(product.serving_size, 80),
        per100g: {
          caloriesKcal: finiteOrNull(nutrients["energy-kcal_100g"]),
          proteinGrams: finiteOrNull(nutrients.proteins_100g),
          carbohydrateGrams: finiteOrNull(nutrients.carbohydrates_100g),
          fatGrams: finiteOrNull(nutrients.fat_100g),
          fiberGrams: finiteOrNull(nutrients.fiber_100g),
        },
        url: cleanText(product.url, 300),
      });
    }).filter((line) => line.includes('"name":"') && !line.includes('"name":""')).join("\n");
  } catch (error) {
    console.warn("Nutrition reference lookup failed", String(error));
    return "";
  }
}

function finiteOrNull(value) {
  const number = Number(value);
  return Number.isFinite(number) && number >= 0 ? number : null;
}

function normalizeNutrition(value) {
  if (!value || typeof value !== "object") return null;
  const foodName = cleanText(value.foodName, 160);
  const caloriesKcal = finiteOrNull(value.caloriesKcal);
  if (!foodName || caloriesKcal === null) return null;
  return {
    foodName,
    servingDescription: cleanText(value.servingDescription, 120),
    servingGrams: finiteOrNull(value.servingGrams),
    caloriesKcal,
    minimumCaloriesKcal: finiteOrNull(value.minimumCaloriesKcal),
    maximumCaloriesKcal: finiteOrNull(value.maximumCaloriesKcal),
    proteinGrams: finiteOrNull(value.proteinGrams),
    carbohydrateGrams: finiteOrNull(value.carbohydrateGrams),
    fatGrams: finiteOrNull(value.fatGrams),
    fiberGrams: finiteOrNull(value.fiberGrams),
    confidence: ["high", "medium", "low"].includes(value.confidence) ? value.confidence : "low",
    assumptions: Array.isArray(value.assumptions) ? value.assumptions.map((item) => cleanText(item, 240)).filter(Boolean).slice(0, 8) : [],
    sources: Array.isArray(value.sources) ? value.sources.map((item) => cleanText(item, 300)).filter(Boolean).slice(0, 8) : [],
  };
}

function normalizeRecentMessages(value) {
  if (!Array.isArray(value)) {
    return [];
  }

  return value
    .slice(-12)
    .map((message) => ({
      role:
        cleanText(message?.role, 16) === "assistant"
          ? "assistant"
          : "user",
      text: cleanText(message?.text, 4000),
    }))
    .filter((message) => message.text);
}

function normalizeMemoryCategory(value) {
  const category = cleanText(value, 40).toLowerCase();
  const allowed = new Set([
    "goal",
    "preference",
    "lifestyle",
    "constraint",
    "event",
    "coach_style",
  ]);

  return allowed.has(category) ? category : "";
}

function cleanText(value, maximumLength) {
  if (typeof value !== "string") {
    return "";
  }
  return value.trim().slice(0, maximumLength);
}

async function sha256(value) {
  const bytes = new TextEncoder().encode(value);
  const digest = await crypto.subtle.digest("SHA-256", bytes);
  return Array.from(new Uint8Array(digest))
    .map((byte) => byte.toString(16).padStart(2, "0"))
    .join("");
}

function friendlyError(error) {
  const message = error instanceof Error ? error.message : String(error);
  const lower = message.toLowerCase();

  if (lower.includes("workers ai binding is not configured")) {
    return "Cloud Coach AI is not configured";
  }

  if (
    lower.includes("rate limit") ||
    lower.includes("quota") ||
    lower.includes("429")
  ) {
    return "Cloud Coach daily usage limit is temporarily exhausted";
  }

  if (
    lower.includes("timeout") ||
    lower.includes("temporarily") ||
    lower.includes("overloaded") ||
    lower.includes("capacity") ||
    lower.includes("503")
  ) {
    return "Cloud Coach is temporarily busy. Please try again shortly.";
  }

  if (lower.includes("empty response") || lower.includes("empty answer")) {
    return "Cloud Coach could not produce a valid response";
  }

  return "Cloud Coach temporarily unavailable";
}

function jsonResponse(payload, status = 200) {
  return new Response(JSON.stringify(payload), {
    status,
    headers: {
      "Content-Type": "application/json; charset=utf-8",
      "Cache-Control": "no-store",
      "X-Content-Type-Options": "nosniff",
    },
  });
}
