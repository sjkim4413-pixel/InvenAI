"""Continue a reviewed checkpoint and choose best weights by separated validation."""
import argparse
import json
import os
from pathlib import Path
import sys

BACKEND = Path(__file__).resolve().parents[2]
sys.path.insert(0,str(BACKEND))
from audit_dataset import audit_dataset


if __name__=='__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--dataset',type=Path,required=True)
    parser.add_argument('--weights',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    args = parser.parse_args()
    audit = audit_dataset(args.dataset)
    if not audit['ready_for_training']:
        raise ValueError(audit['errors'])
    os.environ.update(YOLO_AUTOINSTALL='false',ULTRALYTICS_OFFLINE='true',WANDB_MODE='disabled',
                      YOLO_CONFIG_DIR=str(args.output.resolve()/'yolo'))
    import torch
    from ultralytics import YOLO
    torch.set_num_threads(6)
    model = YOLO(str(args.weights.resolve(strict=True)))
    model.train(data=str((args.dataset/'data.yaml').resolve(strict=True)),imgsz=640,batch=4,
                device='cpu',workers=0,project=str(args.output.resolve()),name='candidate',
                epochs=48,patience=16,amp=False,freeze=0,optimizer='AdamW',lr0=.0006,
                lrf=.10,warmup_epochs=2,mosaic=.25,close_mosaic=10,mixup=0.,scale=.35,
                translate=.08,degrees=5.,shear=3.,perspective=.00025,fliplr=.5,seed=43,
                plots=False,verbose=False)
    print('CHECKPOINT',model.trainer.best)
