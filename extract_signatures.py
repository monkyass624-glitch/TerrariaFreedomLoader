#!/usr/bin/env python3
"""Extract method/field metadata references for Terraria Mobile metadata v31.
Types are emitted as raw Il2Cpp TypeIndex values; resolving them requires runtime
Il2CppType registration or a fully validated MetadataRegistration map.
"""
import csv, json, struct, os
META='/mnt/data/global-metadata.dat'
OUT='/mnt/data/FreedomLoader-analysis'
m=open(META,'rb').read()
magic,ver=struct.unpack_from('<Ii',m,0)
if magic != 0xFAB11BAF or ver != 31:
    raise SystemExit(f'Expected metadata v31, got magic={magic:#x} version={ver}')
sections=['stringLiteral','stringLiteralData','string','events','properties','methods','parameterDefaultValues','fieldDefaultValues','fieldAndParameterDefaultValueData','fieldMarshaledSizes','parameters','fields','genericParameters','genericParameterConstraints','genericContainers','nestedTypes','interfaces','vtableMethods','interfaceOffsets','typeDefinitions','images','assemblies','fieldRefs','referencedAssemblies','attributeData','attributeDataRanges','unresolvedVirtualCallParameterTypes','unresolvedVirtualCallParameterRanges','windowsRuntimeTypeNames','windowsRuntimeStrings','exportedTypeDefinitions']
sec={n:struct.unpack_from('<ii',m,8+i*8) for i,n in enumerate(sections)}
def s(idx):
    off,size=sec['string']
    if idx<0 or idx>=size: return f'<bad:{idx}>'
    p=off+idx; q=m.find(b'\0',p,min(off+size,p+4096))
    if q<0:q=min(off+size,p+4096)
    return m[p:q].decode('utf-8','replace')

to,ts=sec['typeDefinitions']; mo,ms=sec['methods']; po,ps=sec['parameters']; fo,fs=sec['fields']
assert ts%88==0 and ms%36==0 and ps%12==0 and fs%12==0
method_count=ms//36; param_count=ps//12; field_count=fs//12
methods=[]
for i in range(method_count):
    o=mo+i*36
    nameidx,decl,ret,retparam,pstart,generic,token=struct.unpack_from('<IiiiiII',m,o)
    flags,iflags,slot,pcount=struct.unpack_from('<HHHH',m,o+28)
    methods.append(dict(index=i,name=s(nameidx),decl=decl,ret=ret,pstart=pstart,pcount=pcount,token=token,flags=flags))
params=[]
for i in range(param_count):
    ni,tok,ti=struct.unpack_from('<IIi',m,po+i*12)
    params.append((s(ni),tok,ti))
fields=[]
for i in range(field_count):
    ni,ti,tok=struct.unpack_from('<IiI',m,fo+i*12)
    fields.append((s(ni),ti,tok))
# Target list from previous phase; include all overloads by selected method name.
targets={
'Terraria.Main':{'Initialize','DoUpdate','ToggleGameplayUpdates'},
'Terraria.Player':{'UpdateBuffs','UpdateEquips','ResetEffects','Hurt','Spawn'},
'Terraria.NPC':{'SetDefaults','NewNPC','StrikeNPC','UpdateNPC','NPCLoot','checkDead'},
'Terraria.Projectile':{'SetDefaults','NewProjectile','Damage','Update','AI','Kill'},
'Terraria.WorldGen':{'GenerateWorld','StartHardmode','PlaceTile','KillTile','UpdateWorld'},
'Terraria.Item':{'SetDefaults','NewItem','TurnToAir'},
'Terraria.NetMessage':{'SendData','SendTileSquare','SendSection'},
'Terraria.Netplay':{'StartServer','StartListening'},
}
rows=[]; selected=[]
for i in range(ts//88):
    o=to+i*88
    vals=struct.unpack_from('<8I8i8H2I',m,o)
    nameidx,nsidx,byval,decl,parent,elem,generic,flags,fieldstart,methodstart,eventstart,propstart,nestedstart,interfacesstart,vtablestart,ioffstart,mcount,pcount,fcount,ecount,ncount,vcount,icount,iocount,bitfield,tok=vals
    ns=s(nsidx); name=s(nameidx); fq=(ns+'.' if ns else '')+name
    if fq not in targets: continue
    selected.append((i,fq,fieldstart,fcount,methodstart,mcount))
    for meth in methods[methodstart:methodstart+mcount]:
        if meth['name'] not in targets[fq]: continue
        ps0=meth['pstart']; pc=meth['pcount']
        if ps0<0 or ps0+pc>len(params):
            pitems=[]; pdesc='INVALID_RANGE'
        else:
            pitems=params[ps0:ps0+pc]
            pdesc='; '.join(f'{j}:{p[0]} [typeIndex={p[2]}]' for j,p in enumerate(pitems))
        rows.append([fq,meth['name'],meth['index'],f"0x{meth['token']:08X}",meth['ret'],pc,pdesc])
with open(OUT+'/terraria_method_signature_indices.csv','w',newline='',encoding='utf-8') as f:
    w=csv.writer(f);w.writerow(['class','method','metadata_method_index','method_token','return_type_index','parameter_count','parameters_name_and_raw_type_index']);w.writerows(rows)
with open(OUT+'/terraria_field_type_indices.csv','w',newline='',encoding='utf-8') as f:
    w=csv.writer(f);w.writerow(['class','field_name','field_index','raw_type_index','token'])
    for i,fq,fs0,fc,ms0,mc in selected:
        for k,(n,ti,tok) in enumerate(fields[fs0:fs0+fc]):
            w.writerow([fq,n,fs0+k,ti,f'0x{tok:08X}'])
print('target classes',len(selected),'target method overloads',len(rows),'parameters section entries',len(params))
for r in rows[:25]: print(r)
