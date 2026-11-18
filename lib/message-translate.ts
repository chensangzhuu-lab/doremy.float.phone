// lib/message-translate.ts
// 聊天消息翻译：给消息长按菜单的「翻译」用。
// 与思维链翻译共用「辅助 API → 思维链翻译」绑定（未设置时回退全局默认 API），
// 但提示词不同（面向对白/旁白，保留动作描写），并自带内存缓存避免重复计费。

import { resolveAuxiliaryApiConfig } from "./settings-storage";
import { simpleLLMCall } from "./api-helpers";

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

/** 把指令和原文合并成一条 user 消息：有些模型对 system 服从度低，内联更可靠 */
function buildTranslateUserMessage(text: string): string {
    return `${TRANSLATE_INSTRUCTION}\n\n【待翻译内容】\n"""\n${text}\n"""`;
}

const cache = new Map<string, string>();
const CACHE_MAX = 80;

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
    try {
        const result = await simpleLLMCall(apiConfig, [
            { role: "user", content: buildTranslateUserMessage(trimmed) },
        ], { temperature: 0.2, signal: options?.signal ?? controller?.signal });

        if (!result.content?.trim()) {
            return { error: result.error || "翻译失败，请重试" };
        }

        if (cache.size >= CACHE_MAX) {
            const oldest = cache.keys().next().value;
            if (oldest !== undefined) cache.delete(oldest);
        }
        cache.set(trimmed, result.content.trim());
        return { content: result.content.trim() };
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