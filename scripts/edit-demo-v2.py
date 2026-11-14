#!/usr/bin/env python3
"""Reframe real app recordings for a silent product demonstration.

Original sources and v1 exports remain untouched. No UI is recreated. Spatial
crops are recorded separately from unchanged source-time intervals.
"""
import argparse
import importlib.util
import json
import math
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont, ImageFilter
from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont

SPEC = importlib.util.spec_from_file_location('demo_editor', Path(__file__).with_name('edit-demo.py'))
base = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(base)


def linewrap(text, limit=32):
    words = text.replace('\n', ' ').split()
    lines = ['']
    for word in words:
        addition = (' ' if lines[-1] else '') + word
        if len(lines[-1] + addition) > limit:
            lines.append(word)
        else:
            lines[-1] += addition
    if len(lines) > 2:
        raise ValueError('Caption exceeds two lines')
    return '\n'.join(lines)


def fixed_font(original, weight, output):
    face = TTFont(original)
    if 'fvar' in face:
        face = instantiateVariableFont(face, {'wght': weight}, inplace=False)
    face.save(output)
    return output


def source_filters(scene):
    return (f"fps=30:start_time=0,tpad=stop_mode=clone:stop_duration={scene['duration']},"
            f"trim=start={scene['start']}:end={scene['end']},setpts=PTS-STARTPTS")


def text_filter(font, file, size, x, y, color='F5F7F8', extra=''):
    return (f'drawtext=fontfile={font}:textfile={file}:expansion=none:fontsize={size}:'
            f'fontcolor=0x{color}:x={x}:y={y}:line_spacing=7{extra}')


def mobile_background(scene, index, folder, font, bold):
    if index == 3:
        title, subtitle = '하루 일기를\n직접 다듬습니다.', '차량에서 남긴 이야기를 내 문장으로.'
    elif index == 8:
        title, subtitle = '내가 쓴 일기와\n새 초안을 비교합니다.', '직접 고친 문장은 그대로 남습니다.'
    else:
        title, subtitle = '기록은 남기고,\n참고 범위를 고릅니다.', '이후 대화에서 제외할 수 있습니다.'
    image = Image.new('RGBA', (1920,1080), '#F5F7F8')
    draw = ImageDraw.Draw(image)
    draw.text((140,110), '도란', font=ImageFont.truetype(str(font),34), fill='#315C78')
    draw.multiline_text((140,380),title,font=ImageFont.truetype(str(bold),64),fill='#20292F',spacing=20)
    draw.text((142,580),subtitle,font=ImageFont.truetype(str(font),29),fill='#65737B')
    shadow = Image.new('RGBA', image.size)
    ImageDraw.Draw(shadow).rounded_rectangle((1199,41,1687,1067),radius=45,fill=(20,40,55,60))
    image = Image.alpha_composite(image,shadow.filter(ImageFilter.GaussianBlur(22)))
    ImageDraw.Draw(image).rounded_rectangle((1203,28,1677,1052),radius=43,fill='#20292F')
    background = folder/f'{index:02}-background.png'
    image.save(background)
    return background


def render_scene(scene, index, output, font, bold, mask):
    filters = source_filters(scene)
    if scene['surface'] == 'Pleos':
        if scene.get('virtual_driving'):
            filters += ',scale=1920:1080,setsar=1'
            label = output.with_suffix('.context.txt');label.write_text('가상 주행 상태')
            filters += ','+text_filter(font,label,18,48,758,'315C78',':box=1:boxcolor=0xF5F7F8@0.96:boxborderw=8')
        else:
            filters += ',crop=1250:704:642:92,scale=1856:1044,pad=1920:1080:32:18:color=0x20292F,setsar=1'
        for ci,caption in enumerate(scene['captions']):
            path=output.with_suffix(f'.caption-{ci}.txt');path.write_text(linewrap(caption['text']))
            driving=scene.get('virtual_driving')
            filters += ','+text_filter(font,path,26,48 if driving else 82,803 if driving else 1000,
                                      'F5F7F8' if driving else '9CC7DE',
                                      f":box=1:boxcolor=0x20292F@0.94:boxborderw=12:enable='gte(t,{caption['start_seconds']})*lt(t,{caption['end_seconds']})'")
        command=['ffmpeg','-v','warning','-n','-filter_threads','1','-i',str(scene['source']),'-vf',filters,'-map','0:v:0']
    else:
        background=mobile_background(scene,index,output.parent,font,bold)
        # Keep the complete phone viewport stable, including app controls.
        filters += ',scale=450:1000'
        complex_filter=(f'[1:v]{filters},format=rgba[phone];[phone][2:v]alphamerge[rounded];'
                        '[0:v][rounded]overlay=1215:40:shortest=1,setsar=1[v]')
        command=['ffmpeg','-v','warning','-n','-filter_complex_threads','1','-loop','1','-i',str(background),
                 '-i',str(scene['source']),'-loop','1','-i',str(mask),'-filter_complex',complex_filter,'-map','[v]']
    base.run(command+['-t',str(scene['duration']),*base.encode_args(),str(output)])
    length=float(base.probe(output)['format']['duration'])
    if abs(length-scene['duration']) > .04:
        raise RuntimeError(f'Scene {index} changed source timing')


def bookend(source_frame, output, font, headline, end=False):
    image=Image.open(source_frame).convert('RGBA')
    shade=Image.new('RGBA',image.size,(32,41,47,230 if end else 225))
    image=Image.alpha_composite(image,shade)
    draw=ImageDraw.Draw(image)
    draw.text((100,390),'도란',font=ImageFont.truetype(str(font),46),fill='#9CC7DE')
    draw.text((100,480),headline,font=ImageFont.truetype(str(font),42),fill='#F5F7F8')
    backdrop=output.with_suffix('.png');image.convert('RGB').save(backdrop)
    base.run(['ffmpeg','-v','error','-n','-loop','1','-i',str(backdrop),'-t','2',*base.encode_args(),str(output)])


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config',required=True,type=Path)
    parser.add_argument('--output-dir',required=True,type=Path)
    args=parser.parse_args();config=json.loads(args.config.read_text())
    original_font,scenes=base.inspect(config,args.config.resolve().parent)
    out=args.output_dir.resolve();out.mkdir(parents=True,exist_ok=False)
    font=fixed_font(original_font,500,out/'SUIT-medium.ttf');bold=fixed_font(original_font,600,out/'SUIT-semibold.ttf')
    mask=Image.new('L',(450,1000));ImageDraw.Draw(mask).rounded_rectangle((0,0,449,999),radius=33,fill=255)
    mask_path=out/'phone-mask.png';mask.save(mask_path)
    parts=[];evidence=[]
    for i,scene in enumerate(scenes,1):
        part=out/f'{i:02}-scene.mp4';render_scene(scene,i,part,font,bold,mask_path);parts.append(part)
        evidence.append(dict(source=str(scene['source']),sha256=base.digest(scene['source']),
                             start_seconds=scene['start'],end_seconds=scene['end'],core_unedited=bool(scene.get('core_unedited')),
                             crop=[642,92,1250,704] if scene['surface']=='Pleos' and not scene.get('virtual_driving') else None,
                             synthetic_input=scene['synthetic_input'],captions=scene['captions']))
    for file,source,at in [('opening',parts[0],0),('closing',parts[-1],scenes[-1]['duration']-.1)]:
        base.run(['ffmpeg','-v','error','-n','-ss',str(at),'-i',str(source),'-frames:v','1','-update','1',str(out/f'{file}-frame.png')])
    bookend(out/'opening-frame.png',out/'00-intro.mp4',font,'이동 중 나눈 이야기를, 다시 꺼내 읽는 하루로.')
    bookend(out/'closing-frame.png',out/'99-end.mp4',font,'말로 정리한 하루를, 나중에 다시 읽습니다.',True)
    listing=out/'concat.txt';listing.write_text(''.join(f"file '{p.name}'\n" for p in [out/'00-intro.mp4',*parts,out/'99-end.mp4']))
    final=out/'doran-demo-v2.mp4'
    base.run(['ffmpeg','-v','error','-n','-f','concat','-safe','1','-i',str(listing),'-map','0:v:0','-an','-c:v','copy','-movflags','+faststart',str(final)])
    duration=float(base.probe(final)['format']['duration']);expected=sum(s['duration'] for s in scenes)+4
    if abs(duration-expected)>.1:raise RuntimeError('Final timeline length mismatch')
    # SRT uses identical source-time captions, offset by the 2-second opening.
    timeline=2;entries=[]
    for scene in scenes:
        for c in scene['captions']:
            entries.append(f"{len(entries)+1}\n{base.srt_time(timeline+c['start_seconds'])} --> {base.srt_time(timeline+c['end_seconds'])}\n{c['text']}\n")
        timeline += scene['duration']
    (out/'doran-demo-v2.srt').write_text('\n'.join(entries))
    base.run(['ffmpeg','-v','error','-i',str(final),'-f','null','-'])
    report={'status':'rendered_pending_visual_review','duration_seconds':duration,'video_sha256':base.digest(final),
            'version':config['version'],'silent_output':True,'source_time_speed':1,'scenes':evidence,
            'visual_treatment':'Actual app crops; full car context for driving; framed actual phone recordings. No UI reconstruction.',
            'reference':['https://screen.studio/create/product-demo-videos','https://screen.studio/guide/auto-zoom']}
    (out/'edit-manifest-v2.private.json').write_text(json.dumps(report,ensure_ascii=False,indent=2))
    print(final)

if __name__=='__main__':main()
