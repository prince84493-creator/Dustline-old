"""The 7 Bijnor houses (one per uploaded HTML model).  Local frame: x right, z toward the FRONT (door side), y up.
Colours are taken from the HTML models (building3d, house_3d, house3d, house3d (1), ghar_3d_model, villa-3d, house_3d_ultra_realistic)."""


def H(h):
    h = h.lstrip('#')
    return tuple(int(h[i:i + 2], 16) / 255.0 for i in (0, 2, 4))


def chimney(x0, x1, z0, z1, col, up=3.2):
    def f(m, sp, y, info):
        m.box(x0, x1, y[info['n']] - 0.3, y[info['n']] + up, z0, z1, col, coll=True)
        m.box(x0 - .12, x1 + .12, y[info['n']] + up, y[info['n']] + up + .14, z0 - .12, z1 + .12, H('#f0efe9'))
    return f


# ------------------------------------------------------------------ 1. house_3d.html : modern 3 floor, white concrete + wood + glass balconies
S_MODERN = dict(
    name='house_3d_modern', W=10.0, D=11.0, h=[3.1, 3.0, 3.0],
    ext=[H('#ececE8'), H('#e4e2dc'), H('#efefeb')], trim=H('#2b2c2f'), intc=H('#efece4'),
    floorc=[H('#9a7a5a'), H('#8c6e50'), H('#8c6e50')], door_x=-1.4, door_c=H('#2b2c2f'), door_leaf=H('#8a6a4a'),
    stairs=[dict(side='E', z0=0.7), dict(side='W', z0=0.7)],
    balc=dict(floors=[1, 2], depth=2.3, x0=-3.6, x1=3.6, door_x=0.0, glass_rail=True, slabc=H('#efefeb'), floorc=H('#bdb8ae')),
    win={'+z': [[(2.6, 2.0, 1.6)], [(-3.6, 1.1, 1.6), (3.6, 1.1, 1.6)], [(-3.6, 1.1, 1.6), (3.6, 1.1, 1.6)]],
         '+x': [[-2.0, 2.0], [-2.0, 2.0], [-2.0, 2.0]], '-x': [[-2.0, 2.0], [-2.0, 2.0], [-2.0, 2.0]],
         '-z': [[-2.5, 0.0, 2.5], [-2.5, 0.0, 2.5], [-2.5, 0.0, 2.5]]},
    ws=(1.2, 1.4), glass=H('#a9c3cf'),
    roof=dict(type='flat', col=H('#efefeb'), parapet=0.5, pc=H('#efefeb')),
)

# ------------------------------------------------------------------ 2. ghar_3d_model.html : stone basement + 2 plaster floors, hip roof, stone band
S_GHAR = dict(
    name='ghar_3d_model', W=13.0, D=10.0, h=[2.9, 3.0, 3.0],
    ext=[H('#8f8272'), H('#efece5'), H('#efece5')], trim=H('#111111'), intc=H('#ece6da'),
    floorc=[H('#9a6b45'), H('#9a6b45'), H('#9a6b45')], door_x=-3.0, door_c=H('#5a5148'), door_leaf=H('#6b4a30'),
    stairs=[dict(side='E', z0=0.7), dict(side='W', z0=0.7)], band=H('#a8998a'),
    porch=dict(depth=2.2, x0=-4.6, x1=-1.4, floorc=H('#9c9c97')),
    win={'+z': [[(2.6, 2.4, 1.6), (-5.6, 0.9, 1.3)], [(-4.2, 1.3, 1.4), (0.6, 3.0, 1.2), (4.6, 1.0, 1.4)], [(-4.2, 1.3, 1.4), (0.6, 3.0, 1.2), (4.6, 1.0, 1.4)]],
         '+x': [[-2.0, 2.0], [-2.0, 2.0], [-2.0, 2.0]], '-x': [[-2.0, 2.0], [-2.0, 2.0], [-2.0, 2.0]],
         '-z': [[-4.0, -1.0, 2.0, 5.0], [-4.0, -1.0, 2.0, 5.0], [-4.0, -1.0, 2.0, 5.0]]},
    ws=(1.2, 1.4), glass=H('#9fc4d8'),
    roof=dict(type='hip', col=H('#4a3a34'), ov=0.5, slope=0.5, fascia=H('#d9dadb')),
)

# ------------------------------------------------------------------ 3. house_3d_ultra_realistic.html : stucco 2 floor, terracotta hip roof, green shutters, columned porch
S_ULTRA = dict(
    name='house_ultra_realistic', W=11.0, D=9.0, h=[3.1, 3.0],
    ext=[H('#f1dfbf'), H('#f1dfbf')], trim=H('#f8f6f0'), intc=H('#f4ecdc'),
    floorc=[H('#b89f78'), H('#8a6a4a')], door_x=0.0, door_c=H('#f8f6f0'), door_leaf=H('#2f5d50'),
    stairs=[dict(side='E', z0=0.7)], band=H('#c0a77f'),
    porch=dict(depth=2.5, x0=-2.4, x1=2.4, floorc=H('#d6d3cb'), roof=3.1, roofc=H('#f8f6f0'), cols=[-2.0, 2.0], colc=H('#f8f6f0')),
    win={'+z': [[(-4.0, 1.1, 1.5), (4.0, 1.1, 1.5), (-2.4, 0.9, 1.5), (2.4, 0.9, 1.5)], [(-3.9, 1.1, 1.5), (-1.3, 1.1, 1.5), (1.3, 1.1, 1.5), (3.9, 1.1, 1.5)]],
         '+x': [[-1.5, 1.5], [-1.5, 1.5]], '-x': [[-1.5, 1.5], [-1.5, 1.5]], '-z': [[-3.5, 0.0, 3.5], [-3.5, 0.0, 3.5]]},
    ws=(1.1, 1.5), glass=H('#1b2733'), shut=H('#2f5d50'),
    roof=dict(type='hip', col=H('#b9785b'), ov=0.55, slope=0.55, fascia=H('#f8f6f0')),
)

# ------------------------------------------------------------------ 4. villa-3d.html : stone ground floor, cream upper floors, red hip roof, balusters balconies
S_VILLA = dict(
    name='villa_3d', W=14.0, D=10.0, h=[3.2, 3.1, 2.9],
    ext=[H('#9d958a'), H('#f4f0e6'), H('#f4f0e6')], trim=H('#f4f0e6'), intc=H('#f2ece0'),
    floorc=[H('#d2c9b6'), H('#a07a5a'), H('#a07a5a')], door_x=3.0, door_c=H('#f4f0e6'), door_leaf=H('#56636d'),
    stairs=[dict(side='W', z0=0.7), dict(side='E', z0=0.7)], band=H('#f4f0e6'),
    balc=dict(floors=[1, 2], depth=1.9, x0=-6.2, x1=6.2, door_x=-0.5, pillars=[-5.9, -3.0, 0.2, 6.0], pillc=H('#f4f0e6'),
              railc=H('#f4f0e6'), slabc=H('#f4f0e6'), floorc=H('#d2c9b6')),
    win={'+z': [[(-5.2, 1.3, 1.5), (-3.2, 1.3, 1.5)], [(-5.0, 1.4, 1.5), (-2.8, 1.4, 1.5), (3.0, 1.6, 1.5), (5.2, 1.6, 1.5)], [(-5.0, 1.2, 1.4), (-2.8, 1.2, 1.4), (2.6, 1.4, 1.4), (5.0, 1.4, 1.4)]],
         '+x': [[-2.6, 0.0, 2.6], [-2.6, 0.0, 2.6], [-2.6, 0.0, 2.6]], '-x': [[-2.6, 0.0, 2.6], [-2.6, 0.0, 2.6], [-2.6, 0.0, 2.6]],
         '-z': [[-5.2, -3.0, -0.8, 1.6, 3.8, 5.8], [-5.2, -3.0, -0.8, 1.6, 3.8, 5.8], [-4.5, -2.5, -0.5, 2.4, 4.6]]},
    ws=(1.3, 1.5), glass=H('#1f3447'),
    roof=dict(type='hip', col=H('#7a2a20'), ov=0.6, slope=0.6, fascia=H('#f4f0e6')),
    extras=chimney(-2.2, -1.3, -2.6, -1.7, H('#9d958a')),
)

# ------------------------------------------------------------------ 5. building3d.html : apartment block, 5 floors, glass balconies, dark ground-floor glazing
S_APART = dict(
    name='building3d_apartment', W=14.0, D=8.0, h=[3.0, 3.0, 3.0, 3.0, 3.0],
    ext=[H('#2a3440'), H('#f4f4f1'), H('#f4f4f1'), H('#f4f4f1'), H('#f4f4f1')], trim=H('#2b2f33'), intc=H('#efede6'),
    floorc=[H('#c9c5bb')] * 5, door_x=0.0, door_c=H('#2b2f33'), door_leaf=H('#1b2733'),
    stairs=[dict(side='E', z0=0.6), dict(side='W', z0=0.6), dict(side='E', z0=0.6), dict(side='W', z0=0.6)],
    balc=dict(floors=[1, 2, 3, 4], depth=1.7, x0=-6.6, x1=6.6, door_x=0.0, glass_rail=True, slabc=H('#f4f4f1'), floorc=H('#dcdcd8'), railc=H('#2b2f33')),
    win={'+z': [[], [(-4.2, 2.2, 1.6), (4.2, 2.2, 1.6)], [(-4.2, 2.2, 1.6), (4.2, 2.2, 1.6)], [(-4.2, 2.2, 1.6), (4.2, 2.2, 1.6)], [(-4.2, 2.2, 1.6), (4.2, 2.2, 1.6)]],
         '+x': [[-1.4, 1.4]] * 5, '-x': [[-1.4, 1.4]] * 5, '-z': [[-4.5, -1.5, 1.5, 4.5]] * 5},
    ws=(1.3, 1.3), glass=H('#2b3b4a'),
    roof=dict(type='flat', col=H('#f4f4f1'), parapet=0.6, pc=H('#e2e2de')),
)

# ------------------------------------------------------------------ 6. house3d.html : grey siding 2 floor, gable roof, red shutters, white columned porch
S_HOUSE3D = dict(
    name='house3d_siding', W=11.0, D=9.0, h=[2.9, 2.7],
    ext=[H('#8d9195'), H('#8d9195')], trim=H('#f2f2ef'), intc=H('#efe9dc'),
    floorc=[H('#8a6a4a'), H('#8a6a4a')], door_x=-2.6, door_c=H('#f2f2ef'), door_leaf=H('#f2f2ef'),
    stairs=[dict(side='E', z0=0.7)], band=H('#f2f2ef'),
    porch=dict(depth=2.6, x0=-5.4, x1=5.4, floorc=H('#b9b9b4'), roof=3.2, roofc=H('#37312b'), cols=[-5.2, -0.2, 5.2], colc=H('#f2f2ef')),
    win={'+z': [[(2.0, 1.4, 1.5), (4.2, 1.2, 1.5)], [(-3.1, 1.3, 1.5), (1.9, 1.3, 1.5), (4.2, 1.1, 1.5)]],
         '+x': [[-2.0, 1.5], [-2.0, 1.5]], '-x': [[-2.0, 1.5], [-2.0, 1.5]], '-z': [[-3.0, 0.0, 3.0], [-3.0, 0.0, 3.0]]},
    ws=(1.2, 1.5), glass=H('#9ec3e6'), shut=H('#5b1a23'),
    roof=dict(type='gable_x', col=H('#37312b'), ov=0.5, slope=0.5, fascia=H('#f2f2ef')),
)

# ------------------------------------------------------------------ 7. house3d (1).html : beige siding cottage, dark slate roof, stone chimney, white porch
S_COTTAGE = dict(
    name='house3d_cottage', W=10.0, D=8.0, h=[2.8, 2.6],
    ext=[H('#a89678'), H('#a89678')], trim=H('#f0efe9'), intc=H('#f1e9d8'),
    floorc=[H('#8a4a32'), H('#8a4a32')], door_x=0.75, door_c=H('#f0efe9'), door_leaf=H('#56636d'),
    stairs=[dict(side='W', z0=0.7)], band=H('#f0efe9'),
    porch=dict(depth=2.4, x0=-5.0, x1=5.0, floorc=H('#8e8c88'), roof=3.0, roofc=H('#3d4045'), cols=[-4.8, -1.8, 2.0, 4.8], colc=H('#f0efe9')),
    win={'+z': [[(-3.3, 1.0, 1.6), (-1.5, 0.9, 1.6), (3.0, 1.0, 1.6)], [(-3.3, 0.9, 1.4), (-1.2, 0.9, 1.4), (1.2, 0.9, 1.4), (3.3, 0.9, 1.4)]],
         '+x': [[-1.5, 1.5], [-1.5, 1.5]], '-x': [[-1.5, 1.5], [-1.5, 1.5]], '-z': [[-3.0, 0.0, 3.0], [-3.0, 0.0, 3.0]]},
    ws=(1.1, 1.5), glass=H('#23364a'), shut=H('#4a555e'),
    roof=dict(type='gable_x', col=H('#3d4045'), ov=0.5, slope=0.45, fascia=H('#f0efe9')),
    extras=chimney(-4.0, -3.1, -0.6, 0.3, H('#9b8f7c')),
)

SPECS = [S_MODERN, S_GHAR, S_ULTRA, S_VILLA, S_APART, S_HOUSE3D, S_COTTAGE]
