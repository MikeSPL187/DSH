# Удаляет неиспользуемые импорты в Kotlin-файлах (имя не встречается в теле файла).
# Операторные и делегатные имена не трогаем: они используются без упоминания имени.
import sys,re
KEEP={'getValue','setValue','provideDelegate','plus','minus','times','div','rem','invoke','get','set','contains',
      'iterator','next','hasNext','compareTo','rangeTo','rangeUntil','inc','dec','unaryMinus','unaryPlus','not',
      'plusAssign','minusAssign','component1','component2','component3','component4','component5','equals'}
total=0
for p in sys.argv[1:]:
    s=open(p).read()
    lines=s.split('\n')
    imp=[(i,l) for i,l in enumerate(lines) if l.startswith('import ')]
    body='\n'.join(l for l in lines if not l.startswith('import '))
    drop=set()
    seen=set()
    for i,l in imp:
        stmt=l[len('import '):].strip()
        if stmt in seen: drop.add(i); continue
        seen.add(stmt)
        if stmt.endswith('.*'): continue
        name=stmt.split(' as ')[-1].strip() if ' as ' in stmt else stmt.split('.')[-1]
        if name in KEEP: continue
        if not re.search(r'(?<![\w$])'+re.escape(name)+r'(?![\w$])', body): drop.add(i)
    if drop:
        total+=len(drop)
        open(p,'w').write('\n'.join(l for i,l in enumerate(lines) if i not in drop))
print(total)
