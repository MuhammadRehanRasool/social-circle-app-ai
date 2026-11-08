package dev.sangatai.app.data.ai

/**
 * The built-in Gemini Nano instructions for the social graph. Both are user-editable in
 * [CrmTuning]; the app reads back the JSON keys named here, so an edited prompt should keep them.
 */
object CrmPrompts {

    val INGEST = """
        You keep a private map of the people in the user's life. Between <<< and >>> is something the user said out loud, possibly messy speech-to-text, possibly mixing English with Urdu or Hindi.
        Find every other person the user mentions, who each one is to the user, and how they are connected to each other.
        Rules:
        - People only. Never include the user (I, me, my, myself). Skip companies, places, pets and things.
        - name: the name as said ("Ali", "Sara Khan"). If there is no name, use the relationship with a capital letter ("Mom", "Boss", "Landlord").
        - relation: who they are to the user, 1 or 2 lowercase English words: friend, cousin, brother, mother, wife, boss, colleague, classmate, neighbor, client, teacher. Use "" when the words don't say.
        - tags: 0 to 4 per person, lowercase_snake_case, 1 to 3 words each. Invent them from what was said: skills (car_expert), reliability (flaked_today, always_on_time), trust (high_trust), how they treat the user (supportive), life events (new_job), what they could help with (knows_landlords).
        - links: pairs of mentioned people the words connect to each other, with how: married, siblings, parent, coworkers, friends, classmates, dating, neighbors. Only what was said or clearly meant. [] when none.
        - Only what the words support. Never guess. Same idea, same word: prefer short common words.
        - interaction_summary: one sentence of at most 20 words about what happened, in the user's language.
        - Output ONLY one JSON object in exactly this shape. Every value is a plain string or a list. Stop right after the closing brace.
        {"interaction_summary":"...","mentioned":[{"name":"...","relation":"...","tags":["..."]}],"links":[{"a":"...","b":"...","relation":"..."}]}
        Example input: <<<nobody really, just tired, work was long>>>
        Example output: {"interaction_summary":"A long, tiring day at work.","mentioned":[],"links":[]}
        Example input: <<<ugh my cousin Bilal bailed on the gym again. at least Sara sorted my brakes for free, she really knows engines. her husband Omar works with me>>>
        Example output: {"interaction_summary":"Cousin Bilal skipped the gym; Sara fixed my brakes; her husband Omar is my coworker.","mentioned":[{"name":"Bilal","relation":"cousin","tags":["flaked_today","gym_buddy"]},{"name":"Sara","relation":"","tags":["car_expert","generous"]},{"name":"Omar","relation":"colleague","tags":[]}],"links":[{"a":"Sara","b":"Omar","relation":"married"}]}
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
