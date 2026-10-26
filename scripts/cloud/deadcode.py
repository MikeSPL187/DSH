# Объявления (fun/val/class/object), имя которых больше нигде не встречается. Кандидаты на удаление.
import re,glob,collections
srcs=[p for p in glob.glob('**/src/**/*.kt',recursive=True) if '/build/' not in p]
text={p:open(p).read() for p in srcs}
alltext='\n'.join(text.values())
words=collections.Counter(re.findall(r'[A-Za-z_][A-Za-z0-9_]*',alltext))
decl=re.compile(r'^\s*(?:@[\w.]+(?:\([^)]*\))?\s+)*(?:(?:public|internal|private|protected|override|suspend|inline|operator|infix|tailrec|data|sealed|enum|abstract|open|const|lateinit)\s+)*(fun|val|var|class|object|interface)\s+(?:<[^>]+>\s*)?(?:[\w.<>?, ]+\.)?(\w+)',re.M)
skip={'onCreate','onEvent','invoke','main','toString','equals','hashCode','onDispose','Content','Render','run'}
out=[]
for p,s in text.items():
    if '/src/test/' in p: continue
    for m in decl.finditer(s):
        name=m.group(2); line=s[m.start():s.find('\n',m.start())]
        if 'override' in line or name in skip or len(name)<3: continue
        if words[name]<=1: out.append((p,s[:m.start()].count('\n')+1,m.group(1),name))
for o in out: print(f'{o[0]}:{o[1]} {o[2]} {o[3]}')
print(len(out))
