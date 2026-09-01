# Third-party licenses

## Flipping Copilot (BSD 2-Clause)

Parts of this plugin's Grand Exchange highlighting — the widget accessors in
`GeWidgets.java`, the click-by-click highlight decision logic in
`GeHighlightOverlay.java`, and the widget-fill overlay technique — are adapted from the
Flipping Copilot RuneLite plugin (https://github.com/cbrewitt/flipping-copilot), used
under the BSD 2-Clause License. The original copyright notice and license text are
reproduced below as required.

```
BSD 2-Clause License

Copyright (c) 2018, Jasper <Jasper0781@gmail.com>
Copyright (c) 2020, melky <https://github.com/melkypie>
Copyright (c) 2020, Belieal
Copyright (c) 2020, Kyle Richardson
Copyright (c) 2021, Adam Tremonte
Copyright (c) 2024, Cillian Brewitt

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice, this
   list of conditions and the following disclaimer.

2. Redistributions in binary form must reproduce the above copyright notice,
   this list of conditions and the following disclaimer in the documentation
   and/or other materials provided with the distribution.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```

### What we did NOT take

RuneAssist keeps its own backend, suggestion model, flip tracker, UI, and MCP server.
We reused only the client-side GE **widget targeting and highlight** mechanics above; we
did not copy Flipping Copilot's hosted API client, account/session models, price-graph
code, or paid-tier logic. Unlike Flipping Copilot, the highlight here is display-only and
never sets a price or places an offer on the player's behalf.
