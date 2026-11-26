#!/usr/bin/env python3
"""Resolve v31 TypeIndex values using the validated static MetadataRegistration map."""
import csv, json, struct, hashlib
META='/mnt/data/global-metadata.dat'; SO='/mnt/data/libil2cpp.so'
OUT='/mnt/data/FreedomLoader-analysis'
m=open(META,'rb').read(); b=open(SO,'rb').read()
magic,ver=struct.unpack_from('<Ii',m,0)
if magic != 0xFAB11BAF or ver != 31: raise SystemExit('Expected metadata v31')
# Metadata section table
names=['stringLiteral','stringLiteralData','string','events','properties','methods','parameterDefaultValues','fieldDefaultValues','fieldAndParameterDefaultValueData','fieldMarshaledSizes','parameters','fields','genericParameters','genericParameterConstraints','genericContainers','nestedTypes','interfaces','vtableMethods','interfaceOffsets','typeDefinitions','images','assemblies','fieldRefs','referencedAssemblies','attributeData','attributeDataRanges','unresolvedVirtualCallParameterTypes','unresolvedVirtualCallParameterRanges','windowsRuntimeTypeNames','windowsRuntimeStrings','exportedTypeDefinitions']
sec={n:struct.unpack_from('<ii',m,8+i*8) for i,n in enumerate(names)}
def getstr(idx):
    o,n=sec['string']
    if idx<0 or idx>=n:return f'<bad-string:{idx}>'
    p=o+idx;q=m.find(b'\0',p,min(o+n,p+4096))
    if q<0:q=min(o+n,p+4096)
    return m[p:q].decode('utf8','replace')
# ELF VA mapping for supplied exact AArch64 image; data PT_LOAD has p_vaddr-p_offset=0x4000.
def va_to_off(va):
    if 0 <= va < 0x2eade74: return va
    if 0x2eb51b8 <= va < 0x3380000: return va-0x4000
    if 0x3380000 <= va < 0x3396000: return va-0x8000
    raise ValueError(f'VA outside file-backed PT_LOAD: {va:#x}')
# Confirmed MetadataRegistration-like structure in this exact file.
registration=0x2f32510
reg_words=struct.unpack_from('<16Q',b,va_to_off(registration))
# Fields for v27+ metadata registration are count/pointer pairs.
layout=[]
for i in range(0,14,2): layout.append((reg_words[i],reg_words[i+1]))
# observed slots: generic classes, generic instantiations, generic method table, types, method specs, field offsets, type definition sizes
assert reg_words[6] == 22999 and reg_words[7] == 0x2f4b480, (reg_words[6],hex(reg_words[7]))
assert reg_words[10] == 9294 and reg_words[11] == 0x2fe4440
assert reg_words[12] == 9294 and reg_words[13] == 0x2ff66b0
# Parse metadata tables
to,ts=sec['typeDefinitions']; mo,ms=sec['methods']; po,ps=sec['parameters']; fo,fs=sec['fields']
assert ts%88==0 and ms%36==0 and ps%12==0 and fs%12==0
nt=ts//88; nm=ms//36; np=ps//12; nf=fs//12
class_names=[]
for i in range(nt):
    o=to+i*88; nidx,nsidx=struct.unpack_from('<II',m,o)
    ns=getstr(nsidx); name=getstr(nidx); class_names.append((ns+'.' if ns else '')+name)
# Metadata type pointer array
n_types=reg_words[6]; types_va=reg_words[7]
assert n_types < 200000
ptrs=[struct.unpack_from('<Q',b,va_to_off(types_va)+i*8)[0] for i in range(n_types)]
# Decode type name from Il2CppType structure (data pointer/index + bits).
ENUM={0x01:'System.Void',0x02:'System.Boolean',0x03:'System.Char',0x04:'System.SByte',0x05:'System.Byte',0x06:'System.Int16',0x07:'System.UInt16',0x08:'System.Int32',0x09:'System.UInt32',0x0a:'System.Int64',0x0b:'System.UInt64',0x0c:'System.Single',0x0d:'System.Double',0x0e:'System.String',0x0f:'PTR',0x10:'BYREF',0x11:'VALUETYPE',0x12:'CLASS',0x13:'VAR',0x14:'ARRAY',0x15:'GENERICINST',0x16:'System.TypedReference',0x18:'System.IntPtr',0x19:'System.UIntPtr',0x1b:'FNPTR',0x1c:'System.Object',0x1d:'SZARRAY',0x1e:'MVAR',0x1f:'CMOD_REQD',0x20:'CMOD_OPT',0x21:'INTERNAL'}
def decode_type(index,depth=0):
    if index < 0 or index >= len(ptrs): return f'<invalid-TypeIndex:{index}>'
    if depth>3:return f'<recursive-TypeIndex:{index}>'
    ptr=ptrs[index]
    try:
        off=va_to_off(ptr)
        data,bits=struct.unpack_from('<QI',b,off)
    except Exception:
        return f'<invalid-type-ptr:{ptr:#x}>'
    code=(bits>>16)&0xff
    byref=(bits>>29)&1
    pinned=(bits>>30)&1
    base=ENUM.get(code,f'IL2CPP_TYPE_{code:#x}')
    if code in (0x11,0x12):
        if data < len(class_names): base=class_names[data]
        else: base=f'<invalid-TypeDefIndex:{data}>'
    elif code in (0x13,): base=f'!{data}'
    elif code in (0x1e,): base=f'!!{data}'
    elif code in (0x0f,0x10,0x1d):
        # In these forms data is a native type pointer, not a metadata TypeIndex.
        try:
            off2=va_to_off(data); d2,b2=struct.unpack_from('<QI',b,off2); c2=(b2>>16)&0xff
            inner=ENUM.get(c2,f'IL2CPP_TYPE_{c2:#x}')
            if c2 in (0x11,0x12) and d2<len(class_names):inner=class_names[d2]
            base={'0xf':'','0x10':'','0x1d':''}.get(hex(code),'') + (inner+'*' if code==0x0f else inner+'&' if code==0x10 else inner+'[]')
        except Exception: base += f'(data={data:#x})'
    if byref and code not in (0x10,): base+='&'
    if pinned: base+=' [pinned]'
    return base
# methods + parameters
methods=[]
for i in range(nm):
    o=mo+i*36
    nameidx,decl,ret,retparam,pstart,generic,token=struct.unpack_from('<IiiiiII',m,o)
    flags,iflags,slot,pcount=struct.unpack_from('<HHHH',m,o+28)
    methods.append((getstr(nameidx),decl,ret,pstart,pcount,token,flags))
params=[]
for i in range(np):
    ni,tok,ti=struct.unpack_from('<IIi',m,po+i*12);params.append((getstr(ni),tok,ti))
fields=[]
for i in range(nf):
    ni,ti,tok=struct.unpack_from('<IiI',m,fo+i*12);fields.append((getstr(ni),ti,tok))
targets={
'Terraria.Main':{'Initialize','DoUpdate','ToggleGameplayUpdates'},
'Terraria.Player':{'UpdateBuffs','UpdateEquips','ResetEffects','Hurt','Spawn'},
'Terraria.NPC':{'SetDefaults','NewNPC','StrikeNPC','UpdateNPC','NPCLoot','checkDead'},
'Terraria.Projectile':{'SetDefaults','NewProjectile','Damage','Update','AI','Kill'},
'Terraria.WorldGen':{'GenerateWorld','StartHardmode','PlaceTile','KillTile','UpdateWorld'},
'Terraria.Item':{'SetDefaults','NewItem','TurnToAir'},
'Terraria.NetMessage':{'SendData','SendTileSquare','SendSection'},
'Terraria.Netplay':{'StartServer','StartListening'},}
# type table sanity: every pointer resolves to a 16-byte structure in a file-backed segment
checked=0
for p in ptrs:
    o=va_to_off(p)
    assert o+12<=len(b)
    checked+=1
rows=[]; class_idxs=[]
for ti,fq in enumerate(class_names):
    if fq not in targets:continue
    o=to+ti*88
    vals=struct.unpack_from('<8I8i8H2I',m,o)
    fs0,ms0=vals[8],vals[9]; mc,fc=vals[16],vals[18]
    class_idxs.append((ti,fq,fs0,fc))
    for mi in range(ms0,ms0+mc):
        mn,decl,ret,pstart,pcount,tok,flags=methods[mi]
        if mn not in targets[fq]:continue
        pslice=params[pstart:pstart+pcount] if pcount else []
        if pcount and (pstart<0 or pstart+pcount>len(params)): raise ValueError(f'bad params range for {fq}.{mn}')
        prm=', '.join(f'{pn}: {decode_type(pt)} (TypeIndex {pt})' for pn,_,pt in pslice)
        rows.append([fq,mn,mi,f'0x{tok:08X}',decode_type(ret),ret,pcount,prm, 'static' if (flags&0x10) else 'instance/unknown',f'0x{flags:04X}'])
with open(OUT+'/terraria_method_signatures_resolved.csv','w',newline='',encoding='utf-8') as f:
    w=csv.writer(f);w.writerow(['class','method','metadata_method_index','method_token','return_type','return_type_index','parameter_count','parameters','metadata_static_bit','method_flags']);w.writerows(rows)
# Field offsets: index->pointer array, one int32 per field of a class
fo_count,fieldoffsets_va=reg_words[10],reg_words[11]
assert fo_count==nt
fieldptrs=[struct.unpack_from('<Q',b,va_to_off(fieldoffsets_va)+i*8)[0] for i in range(fo_count)]
fieldrows=[]
for ti,fq,fs0,fc in class_idxs:
    p=fieldptrs[ti]
    if not p: continue
    poff=va_to_off(p)
    for j,(fname,ftype,ftok) in enumerate(fields[fs0:fs0+fc]):
        raw=struct.unpack_from('<i',b,poff+j*4)[0]
        fieldrows.append([fq,fname,fs0+j,decode_type(ftype),ftype,raw,f'0x{ftok:08X}'])
with open(OUT+'/terraria_field_offsets_resolved.csv','w',newline='',encoding='utf-8') as f:
    w=csv.writer(f);w.writerow(['class','field','metadata_field_index','field_type','type_index','raw_field_offset','field_token']);w.writerows(fieldrows)
reg={
 'metadata_version':ver,'metadata_registration_candidate_rva':hex(registration),
 'metadata_registration_pairs':[{'count':layout[i][0],'pointer_rva':hex(layout[i][1])} for i in range(len(layout))],
 'types_count':n_types,'types_pointer_array_rva':hex(types_va),
 'field_offsets_count':fo_count,'field_offsets_pointer_array_rva':hex(fieldoffsets_va),
 'type_definition_sizes_count':reg_words[12],'type_definition_sizes_pointer_array_rva':hex(reg_words[13]),
 'resolved_target_method_overloads':len(rows),'resolved_target_fields_with_offsets':len(fieldrows),
 'all_type_pointers_checked':checked,
 'caveat':'Static registration mapping validated by observed count/pointer structure and type records; class field offsets are raw IL2CPP offsets and require runtime validation for static/value-type distinctions.'}
with open(OUT+'/metadata_registration_and_signatures.json','w',encoding='utf-8') as f:json.dump(reg,f,indent=2)
print(json.dumps(reg,indent=2))
print('\nSIGNATURES:')
for r in rows: print(r[0]+'.'+r[1], '->',r[4], '('+r[7]+')', r[8],r[9])
