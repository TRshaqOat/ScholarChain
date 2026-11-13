import sys
S='modules/services/src/main/scala/scholarchain/services/'
patches = {
 'D1-key-in-log': ('Http.scala', 'log.request(name, traceId, requestId, ex.getRequestMethod, template, response.status, ms, code)',
                   'log.request(name, traceId, requestId, ex.getRequestMethod, template + " " + h.getFirst("X-Api-Key"), response.status, ms, code)'),
 'D2-explain-raw-audit': ('Services.scala', 'case Right(Some(a)) => Response.ok(ExplainResponse.of(a))',
                   'case Right(Some(a)) => Response(200, upickle.default.writeJs(a))'),
 'D3-plain-equality': ('Http.scala', 'MessageDigest.isEqual(digest(header.getOrElse("")), expected)',
                   'header.contains(key)'),
 'D4-no-id-format-check': ('Services.scala', 'if !IdFormat.isEvidenceId(id) then throw notFound', '()'),
 'D5-conflict-runs': ('Services.scala', 'case Right(Stored(LoadOutcome.Conflict, _))  =>',
                   'case Right(Stored(LoadOutcome.Conflict, _))  => answer(r, body, minted)\n          case Right(Stored(LoadOutcome.Conflict, _)) if false =>'),
}
f, old, new = patches[sys.argv[1]]
s = open(S+f).read()
assert s.count(old) == 1, (sys.argv[1], s.count(old))
open(S+f, 'w').write(s.replace(old, new))
print('patched', sys.argv[1], f)
