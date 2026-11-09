"""Generate the camera prop models (tripod head/legs, drone, rotor, rail marker) and their palette texture.

Models are plain item models shown by client-only item displays (see cinema/Props.java). Lens faces +Z (south) once rendered,
so a prop with entity yaw 0 looks the same way a camera with yaw 0 does (the files are mirrored, see mirror_z).
"""
import json, os
from PIL import Image

A = os.path.join(os.path.dirname(__file__), '..', 'src/main/resources/assets/lensbridge')
PAL = ['#1d1d22', '#3b3d45', '#8c9099', '#3d6ea8', '#e23b3b', '#e9e9ee', '#f08a24', '#f5c542']
BODY, DARK, METAL, GLASS, RED, WHITE, ORANGE, YELLOW = range(8)

img = Image.new('RGBA', (16, 16))
for i, c in enumerate(PAL):
    x0, y0 = (i % 4) * 4, (i // 4) * 4
    for y in range(y0, y0 + 4):
        for x in range(x0, x0 + 4):
            img.putpixel((x, y), tuple(int(c[k:k + 2], 16) for k in (1, 3, 5)) + (255,))
os.makedirs(A + '/textures/item', exist_ok=True)
img.save(A + '/textures/item/props.png')


def uv(c):
    x0, y0 = (c % 4) * 4, (c // 4) * 4
    return [x0 + 0.5, y0 + 0.5, x0 + 3.5, y0 + 3.5]


def box(frm, to, c, rot=None):
    e = {'from': frm, 'to': to, 'faces': {f: {'uv': uv(c), 'texture': '#t'} for f in ('north', 'south', 'east', 'west', 'up', 'down')}}
    if rot:
        e['rotation'] = rot
    return e


def mirror_z(e):
    # Item displays render items turned 180 degrees, so build the models facing -Z; on screen the lens faces +Z.
    f, t = e['from'], e['to']
    e['from'], e['to'] = [f[0], f[1], 16 - t[2]], [t[0], t[1], 16 - f[2]]
    if 'rotation' in e:
        r = e['rotation']
        r['origin'] = [r['origin'][0], r['origin'][1], 16 - r['origin'][2]]
        if r['axis'] in ('x', 'y'):
            r['angle'] = -r['angle']
    return e


def write(name, elements):
    elements = [mirror_z(e) for e in elements]
    os.makedirs(A + '/models/item', exist_ok=True)
    os.makedirs(A + '/items', exist_ok=True)
    json.dump({'textures': {'t': 'lensbridge:item/props', 'particle': 'lensbridge:item/props'}, 'elements': elements},
              open(f'{A}/models/item/{name}.json', 'w'), indent=1)
    json.dump({'model': {'type': 'minecraft:model', 'model': f'lensbridge:item/{name}'}},
              open(f'{A}/items/{name}.json', 'w'), indent=1)


# camera head: entity position = the lens's point of view (model centre 8,8,8)
write('tripod_head', [
    box([5, 6, 3], [11, 11, 11], BODY),                 # body
    box([6.5, 6.8, 11], [9.5, 9.8, 14], DARK),          # lens barrel
    box([7, 7.3, 14], [9, 9.3, 14.3], GLASS),           # front glass
    box([6, 6.3, 13.5], [10, 10.3, 14], DARK),          # lens hood rim
    box([9.6, 9.2, 1.5], [11.4, 10.8, 4], DARK),        # viewfinder
    box([7.4, 11, 5], [8.6, 12.2, 9], METAL),           # top handle
    box([5.3, 10.1, 10.2], [6.1, 10.8, 11.1], RED),     # tally light
    box([6.5, 5, 5.5], [9.5, 6, 8.5], METAL),           # mount plate
    box([4.9, 7, 5], [5.1, 9, 9], WHITE),               # side label
])
# legs: top at y=16 (just under the head), feet at y=0; stretched in Y to reach the ground
leg = lambda rot: box([7.5, 0, 7.5], [8.5, 16, 8.5], DARK, rot)
write('tripod_legs', [
    box([7.2, 13, 7.2], [8.8, 16, 8.8], METAL),         # centre column
    leg({'origin': [8, 16, 8], 'axis': 'x', 'angle': 22.5}),
    leg({'origin': [8, 16, 8], 'axis': 'z', 'angle': 22.5}),
    leg({'origin': [8, 16, 8], 'axis': 'z', 'angle': -22.5}),
])
# drone: body, two diagonal arms, motors, camera gimbal at the front (+Z); rotors are separate spinning props
arm = lambda a: box([7.4, 7.6, 1], [8.6, 8.4, 15], DARK, {'origin': [8, 8, 8], 'axis': 'y', 'angle': a})
motors = []
for sx in (-1, 1):
    for sz in (-1, 1):
        cx, cz = 8 + sx * 4.95, 8 + sz * 4.95
        motors.append(box([cx - 0.9, 7.4, cz - 0.9], [cx + 0.9, 9.2, cz + 0.9], ORANGE if sz > 0 else METAL))
write('drone', [
    box([5.5, 7, 5.5], [10.5, 9, 10.5], BODY),
    box([6, 9, 6], [10, 9.6, 10], WHITE),
    arm(45), arm(-45), *motors,
    box([7, 5.6, 9.4], [9, 7, 11.2], BODY),             # gimbal camera
    box([7.4, 5.9, 11.2], [8.6, 6.7, 11.4], GLASS),
    box([7.6, 9.6, 5.8], [8.4, 9.9, 6.6], RED),         # status light
])
write('drone_rotor', [
    box([4.6, 7.9, 7.6], [11.4, 8.1, 8.4], BODY),
    box([7.6, 8.1, 7.6], [8.4, 8.5, 8.4], METAL),
])
write('rail_marker', [box([7.2, 7.2, 7.2], [8.8, 8.8, 8.8], YELLOW)])
print('props written')
