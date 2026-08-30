package dev.tokito.physical.dialogue

internal val TOKITO_DIALOGUE_GRAMMAR = """
    root           ::= response | clarification | tool-proposal
    response       ::= "{" ws "\"kind\"" ws ":" ws "\"response\"" ws "," ws "\"text\"" ws ":" ws bounded-string "}" ws
    clarification  ::= "{" ws "\"kind\"" ws ":" ws "\"clarification\"" ws "," ws "\"question\"" ws ":" ws bounded-string "}" ws
    tool-proposal  ::= "{" ws "\"kind\"" ws ":" ws "\"tool_proposal\"" ws "," ws "\"id\"" ws ":" ws bounded-string "," ws "\"tool\"" ws ":" ws bounded-string "," ws "\"version\"" ws ":" ws integer "," ws "\"arguments\"" ws ":" ws object "," ws "\"deadline_ms\"" ws ":" ws integer "," ws "\"rationale\"" ws ":" ws bounded-string "}" ws
    value          ::= object | array | string | number | ("true" | "false" | "null") ws
    object         ::= "{" ws (string ":" ws value ("," ws string ":" ws value)*)? "}" ws
    array          ::= "[" ws (value ("," ws value)*)? "]" ws
    bounded-string ::= "\"" character{1,500} "\"" ws
    string         ::= "\"" character* "\"" ws
    character      ::= [^"\\\x7F\x00-\x1F] | "\\" (["\\bfnrt] | "u" [0-9a-fA-F]{4})
    integer        ::= ("0" | [1-9] [0-9]{0,15}) ws
    number         ::= ("-"? ([0-9] | [1-9] [0-9]{0,15})) ("." [0-9]+)? ([eE] [-+]? [0-9] [1-9]{0,15})? ws
    ws             ::= | " " | "\n" [ \t]{0,20}
""".trimIndent()
