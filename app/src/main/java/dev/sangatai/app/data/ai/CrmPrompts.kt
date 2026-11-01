package dev.sangatai.app.data.ai

/**
 * The built-in Gemini Nano instructions for the social graph. Both are user-editable in
 * [CrmTuning]; the app reads back the JSON keys named here, so an edited prompt should keep them.
 */
object CrmPrompts {

    val INGEST = """
        You keep a private address book for the user. Between <<< and >>> is something the user said out loud, possibly messy speech-to-text, about people in their life.
        Find every other person the user mentions and describe each one with short tags.
        Rules:
        - People only. Never include the user (I, me, my, myself). Skip companies, places, pets and things.
        - name: the name as said ("Ali", "Sara Khan"). If there is no name, use the relationship with a capital letter ("Mom", "Boss", "Landlord").
        - tags: 1 to 4 per person, lowercase_snake_case, 1 to 3 words each. Invent them from what was said: skills (car_expert), reliability (flaked_today, always_on_time), trust (high_trust), how they treat the user (supportive), life events (new_job), what they could help with (knows_landlords).
        - Only tags the words support. Never guess. Same idea, same tag: prefer short common words.
        - interaction_summary: one sentence of at most 20 words about what happened, in the user's language.
        - Output ONLY one JSON object in exactly this shape. Every value is a plain string or a list of plain strings. Stop right after the closing brace.
        {"interaction_summary":"...","mentioned":[{"name":"...","tags":["..."]}]}
        Example input: <<<nobody really, just tired, work was long>>>
        Example output: {"interaction_summary":"A long, tiring day at work.","mentioned":[]}
        Example input: <<<ugh Bilal bailed on the gym again third time this month. at least Sara sorted my brakes for free, she really knows engines>>>
        Example output: {"interaction_summary":"Bilal skipped the gym again; Sara fixed my brakes for free.","mentioned":[{"name":"Bilal","tags":["flaked_today","unreliable_plans","gym_buddy"]},{"name":"Sara","tags":["car_expert","generous","high_trust"]}]}
    """.trimIndent()

    const val INGEST_RETRY_SUFFIX =
        "\nYour previous answer was not valid JSON. Output only the JSON object now, starting with {\"interaction_summary\"."

    val ASK = """
        You help the user find who in their own private network can help with something. Between <<< and >>> under People are people from the user's address book, one per line:
        id | name | tags, each with (times noted, days since last noted)
        Evaluate the query against this local network context. Return the top matches with a 1-sentence specific reason based on their tags.
        Rules:
        - Use only the people listed and only what their tags say. Never invent people, facts or ids.
        - At most 3 matches, best first. A tag noted more times counts more. A recent negative tag (flaked_today, unreliable) counts against someone.
        - reason: one sentence, at most 20 words, that names the tags it relies on.
        - If nobody fits, output {"matches":[]}.
        - Output ONLY one JSON object in exactly this shape. Stop right after the closing brace.
        {"matches":[{"id":"P1","reason":"..."}]}
        Example query: who can help me fix my car
        Example people:
        P4 | Sara | car_expert (3, 2d), high_trust (1, 30d)
        P9 | Bilal | gym_buddy (5, 1d), flaked_today (2, 1d)
        Example output: {"matches":[{"id":"P4","reason":"Tagged car_expert three times and high_trust, so she can likely sort out your car."}]}
    """.trimIndent()

    const val ASK_RETRY_SUFFIX =
        "\nYour previous answer was not valid JSON. Output only {\"matches\":[...]} now."
}
