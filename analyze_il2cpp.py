#!/usr/bin/env python3
import struct, json, csv, os, hashlib, sys
META='/mnt/data/global-metadata.dat'
BIN='/mnt/data/libil2cpp.so'
OUT='/mnt/data/FreedomLoader-analysis'
os.makedirs(OUT, exist_ok=True)

with open(META,'rb') as f: m=f.read()
with open(BIN,'rb') as f: elf=f.read()

def u32(o): return struct.unpack_from('<I',m,o)[0]
def i32(o): return struct.unpack_from('<i',m,o)[0]
def s16(o): return struct.unpack_from('<H',m,o)[0]
magic,ver=struct.unpack_from('<Ii',m,0)
if magic!=0xFAB11BAF or ver!=31: raise SystemExit(f'Unsupported metadata magic/version: {magic:x}/{ver}')

# Metadata v31 header is 256 bytes: 2 leading words + 31 offset/size pairs.
section_names=['stringLiteral','stringLiteralData','string','events','properties','methods','parameterDefaultValues','fieldDefaultValues','fieldAndParameterDefaultValueData','fieldMarshaledSizes','parameters','fields','genericParameters','genericParameterConstraints','genericContainers','nestedTypes','interfaces','vtableMethods','interfaceOffsets','typeDefinitions','images','assemblies','fieldRefs','referencedAssemblies','attributeData','attributeDataRanges','unresolvedVirtualCallParameterTypes','unresolvedVirtualCallParameterRanges','windowsRuntimeTypeNames','windowsRuntimeStrings','exportedTypeDefinitions']
sections={}
for idx,name in enumerate(section_names):
    off,size=struct.unpack_from('<ii',m,8+idx*8)
    if off<0 or size<0 or off+size>len(m): raise SystemExit(f'Bad section {name}: {off} {size}')
    sections[name]=(off,size)

def getstr(index):
    off,size=sections['string']
    if index<0 or index>=size: return f'<bad-string-index:{index}>'
    p=off+index
    q=m.find(b'\0',p,min(off+size,p+4096))
    if q<0: q=min(off+size,p+4096)
    return m[p:q].decode('utf-8','replace')

# Record sizes validated by section divisibility for this v31 file.
type_off,type_sz=sections['typeDefinitions']; method_off,method_sz=sections['methods']
param_off,param_sz=sections['parameters']; field_off,field_sz=sections['fields']
image_off,image_sz=sections['images']
record_sizes={'typeDefinition':88,'methodDefinition':36,'parameterDefinition':12,'fieldDefinition':12,'imageDefinition':40}
for name,(off,size) in [('typeDefinition',sections['typeDefinitions']),('methodDefinition',sections['methods']),('parameterDefinition',sections['parameters']),('fieldDefinition',sections['fields']),('imageDefinition',sections['images'])]:
    rs=record_sizes[name]
    if size%rs: raise SystemExit(f'{name} size {size} not divisible by {rs}')
counts={k:v[1]//record_sizes[k] for k,v in [('typeDefinition',sections['typeDefinitions']),('methodDefinition',sections['methods']),('parameterDefinition',sections['parameters']),('fieldDefinition',sections['fields']),('imageDefinition',sections['images'])]}

# Parse all method definitions first.
methods=[]
for i in range(counts['methodDefinition']):
    o=method_off+i*36
    nameidx,decl,ret,ret_param,paramstart,generic,token=struct.unpack_from('<IiiiiII',m,o)
    flags,iflags,slot,paramcount=struct.unpack_from('<HHHH',m,o+28)
    methods.append({'index':i,'name':getstr(nameidx),'declaringType':decl,'returnType':ret,'parameterStart':paramstart,'parameterCount':paramcount,'token':token,'flags':flags,'iflags':iflags,'slot':slot})

# Parse type definitions.
types=[]
for i in range(counts['typeDefinition']):
    o=type_off+i*88
    vals=struct.unpack_from('<8I8i8H2I',m,o)
    (nameidx,nsidx,byval,decl,parent,element,generic,flags,
     fieldstart,methodstart,eventstart,propstart,nestedstart,interfacesstart,vtablestart,interfaceoffsetstart,
     methodcount,propcount,fieldcount,eventcount,nestedcount,vtablecount,interfacescount,interfaceoffsetcount,
     bitfield,token)=vals
    types.append({'index':i,'name':getstr(nameidx),'namespace':getstr(nsidx),'declaringType':decl,'parentType':parent,
                  'fieldStart':fieldstart,'methodStart':methodstart,'methodCount':methodcount,'fieldCount':fieldcount,
                  'propertyCount':propcount,'eventCount':eventcount,'token':token,'flags':flags,'bitfield':bitfield})

# Validate ranges and build method lists per owner type.
errors=[]
for t in types:
    st=t['methodStart']; ct=t['methodCount']
    if ct and (st<0 or st+ct>len(methods)): errors.append(f"type {t['index']} {t['name']}: method range {st}+{ct} out of bounds")
    else:
        own=methods[st:st+ct]
        for mm in own:
            if mm['declaringType']!=t['index']:
                errors.append(f"owner mismatch type {t['index']} vs method {mm['index']} owner {mm['declaringType']}")
                break

# Parse images and connect their type ranges.
images=[]
for i in range(counts['imageDefinition']):
    o=image_off+i*40
    nameidx,assemblyidx,typestart,typecount,exportstart,exportcount,entry,token,customstart,customcount=struct.unpack_from('<IiIiIiIiIi',m,o)
    images.append({'index':i,'name':getstr(nameidx),'typeStart':typestart,'typeCount':typecount,'assemblyIndex':assemblyidx})

# Game-focused classes: Terraria namespaces and common game systems.
terms=('Terraria','Player','NPC','Projectile','WorldGen','Item','Tile','Main','Collision','Lighting','NetMessage','WorldFile','Chest','Mount','Liquid','Dust','Gore','Recipe','Wiring','MessageBuffer')
focus=[]
for t in types:
    fq=(t['namespace']+'.' if t['namespace'] else '')+t['name']
    if any(term.lower() in fq.lower() for term in terms):
        ms=methods[t['methodStart']:t['methodStart']+t['methodCount']] if t['methodStart']>=0 and t['methodStart']+t['methodCount']<=len(methods) else []
        focus.append({**t,'fullName':fq,'methods':[{'index':x['index'],'name':x['name'],'token':f"0x{x['token']:08X}",'parameterCount':x['parameterCount'],'flags':f"0x{x['flags']:04X}"} for x in ms]})

# Write full index and targeted report.
with open(os.path.join(OUT,'all_types.csv'),'w',newline='',encoding='utf-8') as f:
    w=csv.writer(f); w.writerow(['type_index','namespace','name','declaring_type','parent_type','method_start','method_count','field_count','token'])
    for t in types: w.writerow([t['index'],t['namespace'],t['name'],t['declaringType'],t['parentType'],t['methodStart'],t['methodCount'],t['fieldCount'],f"0x{t['token']:08X}"])
with open(os.path.join(OUT,'focus_types_methods.json'),'w',encoding='utf-8') as f: json.dump(focus,f,ensure_ascii=False,indent=2)
with open(os.path.join(OUT,'focus_types_methods.txt'),'w',encoding='utf-8') as f:
    for t in focus:
        f.write(f"[{t['index']}] {t['fullName']} | methods={t['methodCount']} fields={t['fieldCount']} token=0x{t['token']:08X}\n")
        for mm in t['methods']:
            f.write(f"    ({mm['index']}) {mm['name']} params={mm['parameterCount']} token={mm['token']} flags={mm['flags']}\n")

# Export symbols: retain only il2cpp_* for summary via ELF dynamic symtab using readelf output externally.
summary={
 'metadata_version':ver,'metadata_size':len(m),'metadata_header_size':256,
 'metadata_sha256':hashlib.sha256(m).hexdigest(),'libil2cpp_size':len(elf),'libil2cpp_sha256':hashlib.sha256(elf).hexdigest(),
 'record_counts':counts,'section_count':len(sections),'type_definition_record_size':88,'method_definition_record_size':36,
 'image_count':len(images),'images':images,'focus_type_count':len(focus),'validation_errors':errors[:50],
 'focus_type_names':[x['fullName'] for x in focus]
}
with open(os.path.join(OUT,'summary.json'),'w',encoding='utf-8') as f: json.dump(summary,f,ensure_ascii=False,indent=2)
print(json.dumps(summary,ensure_ascii=False,indent=2))
print('\n--- FOCUS TYPES (first 100) ---')
for t in focus[:100]: print(f"{t['index']:5} {t['fullName']:<65} methods={t['methodCount']:3} fields={t['fieldCount']:3}")
print('\nOUTPUT',OUT)
