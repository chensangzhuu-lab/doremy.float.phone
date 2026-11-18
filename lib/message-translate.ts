// lib/message-translate.ts
// 聊天消息翻译：给消息长按菜单的「翻译」用。
// 与思维链翻译共用「辅助 API → 思维链翻译」绑定（未设置时回退全局默认 API），
// 但提示词不同（面向对白/旁白，保留动作描写），并自带内存缓存避免重复计费。

import { resolveAuxiliaryApiConfig } from "./settings-storage";
import {
    buildChatCompletionsUrl,
    buildRequestHeaders,
    determineBaseUrl,
    isNativeAnthropicApi,
    isNativeGoogleApi,
    simpleLLMCall,
} from "./api-helpers";
import { createSseJsonParser } from "./sse-json";
import type { ApiConfig } from "./settings-types";

const TRANSLATE_INSTRUCTION = [
    "你是专业翻译。把下面【待翻译内容】里的文字忠实翻译成自然、地道的简体中文。",
    "",
    "铁律：",
    "- 这是「翻译」，不是「改写」「续写」或「表演」。原文表达什么意思，就译成什么意思。",
    "- 只输出译文本身，不要复述以上要求，不删减、不扩写、不脑补、不解释、不评论。",
    "- 不要加引号、括号标签或任何包装文字。",
    "",
    "表达要求：",
    "- 原文里用括号写的动作、神态、环境描写，译文里同样用括号保留在对应位置，不要改成叙述句。",
    "- 保留原有换行与段落结构；原文换行处译文也要换行。",
    "- 人名、称呼、专有名词沿用原文或上下文中已有的译法，不要另起译名。",
    "- 用自然的中文，避免明显的翻译腔（欧化句式）。",
].join("\n");

/**
 * 把指令和原文合并成一条 user 消息：有些模型对 system 服从度低，内联更可靠。
 *
 * 结尾刻意留一个【译文】起头：部分模型/中转看到「只输出译文」这类要求会把整段
 * 当成已完成的指令，不再产生正文，直接返回一个只有 role、没有 content 的空消息
 * （用户实报：finishReason=stop，message 字段只有 role）。给它一个待续写的落点，
 * 正文才稳定产出；取回时再把前缀剥掉。
 */
function buildTranslateUserMessage(text: string): string {
    return `${TRANSLATE_INSTRUCTION}\n\n【待翻译内容】\n"""\n${text}\n"""\n\n【译文】`;
}

/** 清掉模型回带的起头标记，避免译文块里多出一行「【译文】」 */
function cleanTranslation(raw?: string | null): string {
    if (!raw) return "";
    return raw.replace(/^\s*【译文】[:：]?\s*/, "").trim();
}

const cache = new Map<string, string>();
const CACHE_MAX = 80;

type OpenAiStreamEvent = {
    choices?: {
        delta?: { content?: string };
        text?: string;
    }[];
};

/**
 * 流式兜底：部分中转站的非流式请求会返回空正文（只给 role），但流式正常。
 * 只覆盖 OpenAI 兼容协议（原生 Anthropic / Gemini 各有一套事件结构，
 * 不值得为此再写两份解析）；失败一律返回空串，由调用方决定怎么报错。
 *
 * 刻意不传 max_tokens：推理模型会把它消耗在思考上，正文反而更容易空。
 */
async function translateViaStream(
    config: ApiConfig,
    prompt: string,
    signal?: AbortSignal,
): Promise<string> {
    if (isNativeAnthropicApi(config) || isNativeGoogleApi(config)) return "";
    const baseUrl = determineBaseUrl(config);
    if (!baseUrl || !config.apiKey) return "";

    const headers = { ...buildRequestHeaders(config, baseUrl), Accept: "text/event-stream" };
    const res = await fetch(buildChatCompletionsUrl(baseUrl), {
        method: "POST",
        headers,
        body: JSON.stringify({
            model: config.defaultModel,
            messages: [{ role: "user", content: prompt }],
            temperature: 0.2,
            stream: true,
        }),
        signal,
    });
    if (!res.ok || !res.body) return "";

    const reader = res.body.getReader();
    const decoder = new TextDecoder();
    const parser = createSseJsonParser();
    let buffer = "";
    let text = "";

    const consume = (events: unknown[]): void => {
        for (const event of events) {
            const item = event as OpenAiStreamEvent;
            const choice = item?.choices?.[0];
            const delta = choice?.delta?.content;
            if (typeof delta === "string" && delta) text += delta;
            else if (typeof choice?.text === "string") text += choice.text;
        }
    };

    const drain = (): void => {
        let index = buffer.indexOf("\n\n");
        while (index !== -1) {
            const chunk = buffer.slice(0, index);
            buffer = buffer.slice(index + 2);
            consume(parser.pushEvent(chunk));
            index = buffer.indexOf("\n\n");
        }
    };

    while (true) {
        const { read } = reader;
        const result = await read.call(reader);
        if (result.done) break;
        buffer += decoder.decode(result.value, { stream: true }).replace(/\r\n/g, "\n");
        drain();
    }
    buffer += decoder.decode().replace(/\r\n/g, "\n");
    if (buffer.trim()) consume(parser.pushEvent(buffer));
    consume(parser.flush());
    return text.trim();
}

/** 两轮都拿到空正文时给一句能照着做的提示，而不是把原始调试串丢给用户 */
function describeEmptyTranslation(apiDetail?: string): string {
    if (apiDetail && /API 错误|请求失败|密钥|API 地址/.test(apiDetail)) return apiDetail;
    return [
        "翻译失败：模型返回了空内容（只回了 role、没有正文）。",
        "这是模型或中转站的兼容问题，不是这条消息的问题。可以：",
        "① 到「设置 → 配置绑定 → 辅助 API」把翻译换成平时聊天能正常回复的模型；",
        "② 再点一次「翻译」重试（偶发的中转抖动重试常常就好了）。",
    ].join("");
}

export async function translateMessageText(
    text: string,
    options?: { signal?: AbortSignal },
): Promise<{ content?: string; error?: string }> {
    const trimmed = (text || "").trim();
    if (!trimmed) return { error: "这条消息没有可翻译的文字" };

    const cached = cache.get(trimmed);
    if (cached) return { content: cached };

    const apiConfig = resolveAuxiliaryApiConfig("reasoningTranslateApiConfigId");
    if (!apiConfig) {
        return { error: "请先在设置 → 配置绑定 → 辅助 API 中设置翻译 API（或设置全局默认 API）" };
    }

    // 默认 90 秒超时，避免请求挂死导致界面一直停在「翻译中」
    const controller = options?.signal ? null : new AbortController();
    const timer = controller ? setTimeout(() => controller.abort(), 90_000) : null;
    const signal = options?.signal ?? controller?.signal;
    const prompt = buildTranslateUserMessage(trimmed);

    try {
        const first = await simpleLLMCall(apiConfig, [{ role: "user", content: prompt }], {
            temperature: 0.2,
            signal,
        });
        let content = cleanTranslation(first.content);

        if (!content) {
            // 非流式返回空正文 → 换流式再要一次（很多中转只有流式才给正文）
            const streamed = await translateViaStream(apiConfig, prompt, signal).catch(() => "");
            content = cleanTranslation(streamed);
            if (!content) return { error: describeEmptyTranslation(first.error) };
        }

        if (cache.size >= CACHE_MAX) {
            const oldest = cache.keys().next().value;
            if (oldest !== undefined) cache.delete(oldest);
        }
        cache.set(trimmed, content);
        return { content };
    } catch (e) {
        if (controller?.signal.aborted) return { error: "翻译超时，请重试" };
        return { error: e instanceof Error ? e.message : "翻译失败，请重试" };
    } finally {
        if (timer) clearTimeout(timer);
    }
}

/** 消息里真正要翻的文字：语音条的文字存在 mediaData.label 里 */
export function getTranslatableMessageText(msg: { content?: string; mediaType?: string; mediaData?: { label?: string } }): string {
    if (msg.mediaType === "audio") return (msg.mediaData?.label || "").trim();
    return (msg.content || "").trim();
}

/**
 * 判断是否「像外文」，决定菜单里要不要出现「翻译」。
 * 日文常夹汉字，必须先按假名判定，否则会被当成中文跳过。
 */
export function looksForeignText(text: string): boolean {
    const t = (text || "").trim();
    if (!t) return false;
    if (/[\u3040-\u30ff]/.test(t)) return true; // 假名
    if (/[\uac00-\ud7af]/.test(t)) return true; // 谚文
    if (/[\u0400-\u04ff]/.test(t)) return true; // 西里尔文
    const letters = t.replace(/[^A-Za-z]/g, "");
    return letters.length >= 4 && !/[\u3400-\u9fff]/.test(t);
}