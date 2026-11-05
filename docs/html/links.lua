-- Links between the guides point at their HTML pages; other Markdown files in the repository at GitHub.
local base = os.getenv("BFM_DOCS_BASE") or "https://github.com/mkurtoglu/bfmv3/blob/main/"
local html = { ["install-rpm.md"] = "install-rpm.html", ["install-rpm.tr.md"] = "install-rpm.tr.html",
  ["user-guide.md"] = "user-guide.html", ["install.md"] = "install.html",
  ["architecture.md"] = "architecture.html", ["architecture.tr.md"] = "architecture.tr.html",
  ["build-packages.md"] = "build-packages.html", ["build-packages.tr.md"] = "build-packages.tr.html" }

function Link(el)
  local target = el.target
  if target:match("^%a+:") or target:match("^#") then
    return el
  end
  local file, anchor = target:match("^([^#]+)(#?.*)$")
  local name = file:match("([^/]+)$")
  if html[name] then
    el.target = html[name] .. anchor
    -- a link that shows the file's name shows the page's name
    if #el.content == 1 and el.content[1].t == "Str" and el.content[1].text == file then
      el.content = { pandoc.Str(html[name]) }
    end
  elseif file:match("%.md$") then
    local path = file:gsub("^%./", "")
    if not path:match("/") and name ~= "README.md" then
      path = "docs/" .. path
    end
    path = path:gsub("^%.%./", "")
    el.target = base .. path .. anchor
  end
  return el
end
