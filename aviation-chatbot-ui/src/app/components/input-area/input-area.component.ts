import { Component, EventEmitter, Input, OnInit, Output } from '@angular/core';

@Component({
  selector: 'app-input-area',
  templateUrl: './input-area.component.html',
  styleUrls: ['./input-area.component.scss']
})
export class InputAreaComponent implements OnInit {

  @Output() sendMessage = new EventEmitter<string>();
  @Input() disabled = false;

  inputText = '';

  constructor() { }

  ngOnInit(): void {
  }

  onSend(): void {
    if (this.inputText.trim() && !this.disabled) {
      this.sendMessage.emit(this.inputText);
      this.inputText = '';
    }
  }
}
